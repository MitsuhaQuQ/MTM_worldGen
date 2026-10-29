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
    private static final int CACHE_CAPACITY = 64;
    /** Spawn search radius in coarse cells (64 * 64 blocks = 4096 blocks by default). */
    private static final int SPAWN_SEARCH_RINGS = 64;
    private static final double SPAWN_MIN_ABOVE_SEA = 3.0;
    private static final double SPAWN_MAX_ABOVE_SEA = 40.0;

    private static final long BEACH_SALT = 0x4245414348L;
    /**
     * Beach depth inland from the coastline varies along the coast between these values in blocks;
     * negative widths leave stretches of coast without a beach.
     */
    private static final double BEACH_MIN_WIDTH = -8.0;
    private static final double BEACH_MAX_WIDTH = 96.0;
    private static final double BEACH_WIDTH_WAVELENGTH = 768.0;
    /** Fractal noise clusters around zero; stretch it so beach widths actually reach both ends of the range. */
    private static final double BEACH_WIDTH_CONTRAST = 2.0;
    /** Sand continues this far offshore as shallow sea floor. */
    private static final double BEACH_OFFSHORE = 32.0;
    /** No beach on land rising more than this above sea level, such as coastal hills. */
    private static final double BEACH_MAX_HEIGHT = 6.0;

    /** The top fraction of the height range is compressed so peaks approach but never exceed the maximum. */
    private static final double SOFT_CAP_FRACTION = 0.13;

    private static final int FIELD_HEIGHT = 0;
    private static final int FIELD_COAST_DISTANCE = 1;

    private static final Map<Long, TerrainLogic> BY_SEED = new ConcurrentHashMap<>();

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
        this.beachWidth = new Fbm(Hashing.derive(seed, BEACH_SALT), 2, BEACH_WIDTH_WAVELENGTH);
        this.regions = new RegionCache(this::generateRegion, CACHE_CAPACITY);
    }

    /** Shared instance for a world seed, using the default settings until per-world config exists. */
    public static TerrainLogic forSeed(long seed) {
        return BY_SEED.computeIfAbsent(seed, s -> new TerrainLogic(s, WorldgenSettings.DEFAULT));
    }

    /** Drops the shared instance for a seed so the next {@link #forSeed} starts with cold caches. For tests. */
    public static void forget(long seed) {
        BY_SEED.remove(seed);
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
        double skeletonHeight = skeleton.height(x, z, base - (settings.seaLevel() - 1));
        return softCap(base + skeletonHeight);
    }

    /** Leaves heights below the cap band untouched and approaches the maximum asymptotically above it. */
    private double softCap(double height) {
        double band = SOFT_CAP_FRACTION * settings.maxSurfaceHeight();
        double start = settings.maxSurfaceHeight() - band;
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
        if (distance < -BEACH_OFFSHORE) {
            return false;
        }
        double noise = Math.max(-1.0, Math.min(1.0, BEACH_WIDTH_CONTRAST * beachWidth.sample(x, z)));
        double width = BEACH_MIN_WIDTH + (BEACH_MAX_WIDTH - BEACH_MIN_WIDTH) * (0.5 + 0.5 * noise);
        return distance < width && sampleSurfaceHeight(x, z) < settings.seaLevel() - 1 + BEACH_MAX_HEIGHT;
    }

    /**
     * Initial world spawn: the coarse cell closest to the origin (searching outward ring by ring) that is
     * comfortably on land, so players do not start in the middle of an ocean.
     *
     * @return block column at the center of the chosen cell, or the origin if no land is found in range
     */
    public BlockColumn findSpawn() {
        double sea = settings.seaLevel() - 1;
        for (int ring = 0; ring <= SPAWN_SEARCH_RINGS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && isSpawnCell(dx, dz, sea)) {
                        int half = settings.cellSize() / 2;
                        return new BlockColumn(dx * settings.cellSize() + half, dz * settings.cellSize() + half);
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
        if (height < sea + SPAWN_MIN_ABOVE_SEA || height > sea + SPAWN_MAX_ABOVE_SEA) {
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
        double size = settings.cellSize();
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
        double size = settings.cellSize();
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
        int size = settings.cellSize();
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
