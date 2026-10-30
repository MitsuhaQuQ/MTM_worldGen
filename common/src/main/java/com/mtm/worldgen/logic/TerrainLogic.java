package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point to the terrain logic of one world. Large-scale values live on the coarse grid and are
 * cached per logic region; block-level values are Catmull-Rom interpolations of cell centers.
 * Everything is a function of seed + coordinates + settings, independent of evaluation order.
 */
public final class TerrainLogic {

    private static final long BEACH_SALT = 0x4245414348L;

    private static final int CHUNK_SIZE = 16;
    /** Horizontal spacing of the corners the terrain density is evaluated at. */
    private static final int NOISE_CELL_WIDTH = 4;


    private static final int FIELD_HEIGHT = 0;
    private static final int FIELD_COAST_DISTANCE = 1;

    private static final Map<WorldKey, TerrainLogic> WORLDS = new ConcurrentHashMap<>();

    private final WorldgenSettings settings;
    private final ContinentLayer continents;
    private final SkeletonLayer skeleton;
    private final Fbm beachWidth;
    private final RegionCache regions;
    private final ThreadLocal<LogicRegion> lastRegion = new ThreadLocal<>();
    private final ThreadLocal<ColumnCache> columns = ThreadLocal.withInitial(ColumnCache::new);

    public TerrainLogic(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.continents = new ContinentLayer(seed, settings);
        this.skeleton = new SkeletonLayer(seed, settings);
        this.beachWidth = new Fbm(Hashing.derive(seed, BEACH_SALT), 2, settings.beaches.widthWavelength);
        this.regions = new RegionCache(this::generateRegion, settings.general.regionCacheSize);
    }

    /** Shared instance for a world, identified by its seed and settings. */
    public static TerrainLogic forWorld(long seed, WorldgenSettings settings) {
        return WORLDS.computeIfAbsent(new WorldKey(seed, settings.toJson()), key -> new TerrainLogic(seed, settings));
    }

    /** Drops the shared instance of a world so the next {@link #forWorld} starts with cold caches. For tests. */
    public static void forget(long seed, WorldgenSettings settings) {
        WORLDS.remove(new WorldKey(seed, settings.toJson()));
    }

    public WorldgenSettings settings() {
        return settings;
    }

    public LogicRegion region(int regionX, int regionZ) {
        return regions.get(regionX, regionZ);
    }

    /** Coarse surface height at a cell center, by global cell index. */
    public double cellHeight(int cellX, int cellZ) {
        return cellValue(FIELD_HEIGHT, cellX, cellZ);
    }

    /**
     * Upper bound for solid blocks and sea water in a chunk. Terrain density is interpolated between noise cell
     * corners, so the highest corner bounds the surface; the margin covers terrain that vanilla raises under
     * structures. Everything above is known to be air, which lets chunk filling stop early.
     */
    public int highestBlockInChunk(int chunkX, int chunkZ) {
        double highest = settings.general.seaLevel;
        for (int dx = 0; dx <= CHUNK_SIZE; dx += NOISE_CELL_WIDTH) {
            for (int dz = 0; dz <= CHUNK_SIZE; dz += NOISE_CELL_WIDTH) {
                highest = Math.max(highest, sampleSurfaceHeight(chunkX * CHUNK_SIZE + dx, chunkZ * CHUNK_SIZE + dz));
            }
        }
        return (int) Math.ceil(highest) + settings.general.structureTerrainMargin;
    }

    /** Surface height at a block column, memoized per thread for repeated queries of the same column. */
    public double surfaceHeight(int x, int z) {
        return columns.get().column(this, x, z).height;
    }

    /** Estimated distance to the coastline at a block column, memoized per thread like {@link #surfaceHeight}. */
    public double coastDistance(int x, int z) {
        return columns.get().column(this, x, z).coastDistance();
    }

    /** Whether a block column gets a sand beach, memoized per thread like {@link #surfaceHeight}. */
    public boolean isBeach(int x, int z) {
        return columns.get().column(this, x, z).beach();
    }

    /**
     * Surface height at any position: the continent base interpolated from the 4x4 surrounding cell centers,
     * plus the terrain skeleton evaluated at the exact position, softly capped below the maximum height.
     */
    public double sampleSurfaceHeight(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double skeletonHeight = skeleton.height(x, z, base - (settings.general.seaLevel - 1));
        return softCap(base + skeletonHeight);
    }

    /** Leaves heights below the cap band untouched and approaches the maximum asymptotically above it. */
    private double softCap(double height) {
        double band = settings.general.softCapFraction * settings.general.maxSurfaceHeight;
        double start = settings.general.maxSurfaceHeight - band;
        if (height <= start) {
            return height;
        }
        return start + band * (1.0 - Math.exp(-(height - start) / band));
    }

    /** Estimated distance to the coastline in blocks: positive inland, negative offshore. */
    public double sampleCoastDistance(double x, double z) {
        return interpolate(FIELD_COAST_DISTANCE, x, z);
    }

    /**
     * Beaches run along every coast with a depth inland that varies along the coast, occasionally
     * dropping to nothing, and extend a little offshore as shallow sandy sea floor.
     */
    public boolean sampleBeach(double x, double z) {
        double distance = sampleCoastDistance(x, z);
        if (distance < -settings.beaches.offshore) {
            return false;
        }
        double noise = Math.max(-1.0, Math.min(1.0, settings.beaches.widthContrast * beachWidth.sample(x, z)));
        double width = settings.beaches.minWidth + (settings.beaches.maxWidth - settings.beaches.minWidth) * (0.5 + 0.5 * noise);
        return distance < width && sampleSurfaceHeight(x, z) < settings.general.seaLevel - 1 + settings.beaches.maxHeight;
    }

    /**
     * Initial world spawn: the coarse cell closest to the origin (searching outward ring by ring) that is
     * comfortably on land, so players do not start in the middle of an ocean.
     *
     * @return block column at the center of the chosen cell, or the origin if no land is found in range
     */
    public BlockColumn findSpawn() {
        double sea = settings.general.seaLevel - 1;
        for (int ring = 0; ring <= settings.spawn.searchRings; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && isSpawnCell(dx, dz, sea)) {
                        int half = settings.general.cellSize / 2;
                        return new BlockColumn(dx * settings.general.cellSize + half, dz * settings.general.cellSize + half);
                    }
                }
            }
        }
        return new BlockColumn(0, 0);
    }

    /**
     * Nearest surface at or above a height, searching square rings outward from an origin.
     *
     * @param step   ring spacing and sample spacing in blocks
     * @param radius search radius in blocks
     * @return the first matching column found, or null if none within the radius
     */
    public BlockColumn findNearestAbove(double minHeight, int originX, int originZ, int step, int radius) {
        for (int ring = 0; ring * step <= radius; ring++) {
            BlockColumn best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = originX + dx * step;
                    int z = originZ + dz * step;
                    double distance = Math.hypot(dx, dz);
                    if (distance < bestDistance && sampleSurfaceHeight(x, z) >= minHeight) {
                        best = new BlockColumn(x, z);
                        bestDistance = distance;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private boolean isSpawnCell(int cellX, int cellZ, double sea) {
        double height = cellCenterSurface(cellX, cellZ);
        if (height < sea + settings.spawn.minAboveSea || height > sea + settings.spawn.maxAboveSea) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (cellCenterSurface(cellX + dx, cellZ + dz) < sea + 1.0) {
                    return false;
                }
            }
        }
        return true;
    }

    private double cellCenterSurface(int cellX, int cellZ) {
        double size = settings.general.cellSize;
        return sampleSurfaceHeight((cellX + 0.5) * size, (cellZ + 0.5) * size);
    }

    private double cellValue(int field, int cellX, int cellZ) {
        int cells = settings.cellsPerRegion();
        int regionX = Math.floorDiv(cellX, cells);
        int regionZ = Math.floorDiv(cellZ, cells);
        LogicRegion region = lastRegion.get();
        if (region == null || region.regionX() != regionX || region.regionZ() != regionZ) {
            region = regions.get(regionX, regionZ);
            lastRegion.set(region);
        }
        int localX = cellX - regionX * cells;
        int localZ = cellZ - regionZ * cells;
        return field == FIELD_HEIGHT ? region.cellHeight(localX, localZ) : region.cellCoastDistance(localX, localZ);
    }

    /** Catmull-Rom interpolation of a cell field from the 4x4 surrounding cell centers. */
    private double interpolate(int field, double x, double z) {
        double size = settings.general.cellSize;
        double u = x / size - 0.5;
        double v = z / size - 0.5;
        int cellX = (int) Math.floor(u);
        int cellZ = (int) Math.floor(v);
        double tx = u - cellX;
        double tz = v - cellZ;
        return catmullRom(row(field, cellX, cellZ - 1, tx), row(field, cellX, cellZ, tx),
                row(field, cellX, cellZ + 1, tx), row(field, cellX, cellZ + 2, tx), tz);
    }

    private double row(int field, int cellX, int cellZ, double t) {
        return catmullRom(cellValue(field, cellX - 1, cellZ), cellValue(field, cellX, cellZ),
                cellValue(field, cellX + 1, cellZ), cellValue(field, cellX + 2, cellZ), t);
    }

    private LogicRegion generateRegion(int regionX, int regionZ) {
        int cells = settings.cellsPerRegion();
        int size = settings.general.cellSize;
        float[] heights = new float[cells * cells];
        float[] coastDistances = new float[cells * cells];
        for (int lz = 0; lz < cells; lz++) {
            for (int lx = 0; lx < cells; lx++) {
                double x = ((long) regionX * cells + lx + 0.5) * size;
                double z = ((long) regionZ * cells + lz + 0.5) * size;
                double value = continents.value(x, z);
                heights[lz * cells + lx] = (float) continents.height(value);
                coastDistances[lz * cells + lx] = (float) continents.coastDistance(x, z, value);
            }
        }
        return new LogicRegion(regionX, regionZ, cells, heights, coastDistances);
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
    }

    private record WorldKey(long seed, String settingsJson) {
    }

    /** Direct-mapped per-thread cache of recently sampled columns (one chunk's worth of slots). */
    private static final class ColumnCache {
        private static final int SLOTS = 256;
        private final Column[] slots = new Column[SLOTS];

        Column column(TerrainLogic logic, int x, int z) {
            int index = (x & 15) | ((z & 15) << 4);
            Column column = slots[index];
            if (column == null || column.owner != logic || column.x != x || column.z != z) {
                column = new Column(logic, x, z);
                slots[index] = column;
            }
            return column;
        }
    }

    /** Per-column values; everything but the height is computed only when first asked for. */
    private static final class Column {
        final TerrainLogic owner;
        final int x;
        final int z;
        final double height;
        private double coastDistance = Double.NaN;
        private int beach = -1;

        Column(TerrainLogic owner, int x, int z) {
            this.owner = owner;
            this.x = x;
            this.z = z;
            this.height = owner.sampleSurfaceHeight(x, z);
        }

        double coastDistance() {
            if (Double.isNaN(coastDistance)) {
                coastDistance = owner.sampleCoastDistance(x, z);
            }
            return coastDistance;
        }

        boolean beach() {
            if (beach < 0) {
                beach = owner.sampleBeach(x, z) ? 1 : 0;
            }
            return beach == 1;
        }
    }
}
