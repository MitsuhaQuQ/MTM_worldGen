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
    private static final long SNOW_SALT = 0x534E4F57L;
    private static final long ROCK_SALT = 0x524F434BL;
    private static final long ROCK_PATCH_SALT = 0x5041544348L;
    /** Block spacing of the finite difference used for the surface grade. */
    private static final double GRADE_STEP = 2.0;

    private static final int CHUNK_SIZE = 16;
    /** Horizontal spacing of the corners the terrain density is evaluated at. */
    private static final int NOISE_CELL_WIDTH = 4;


    private static final int FIELD_HEIGHT = 0;
    private static final int FIELD_COAST_DISTANCE = 1;

    private static final Map<WorldKey, TerrainLogic> WORLDS = new ConcurrentHashMap<>();

    private final WorldgenSettings settings;
    private final ContinentLayer continents;
    private final SkeletonLayer skeleton;
    private final ClimateLayer climate;
    private final BiomeLayer biomes;
    private final Fbm beachWidth;
    private final Fbm snowLineNoise;
    private final Fbm rockNoise;
    private final Fbm rockPatchNoise;
    private final RegionCache<LogicRegion> regions;
    private final RegionCache<FlowRegion> flowRegions;
    private final ThreadLocal<LogicRegion> lastRegion = new ThreadLocal<>();
    private final ThreadLocal<ColumnCache> columns = ThreadLocal.withInitial(ColumnCache::new);

    public TerrainLogic(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.continents = new ContinentLayer(seed, settings);
        this.skeleton = new SkeletonLayer(seed, settings);
        this.climate = new ClimateLayer(seed, settings);
        this.biomes = new BiomeLayer(seed, settings);
        this.beachWidth = new Fbm(Hashing.derive(seed, BEACH_SALT), 2, settings.beaches.widthWavelength);
        this.snowLineNoise = new Fbm(Hashing.derive(seed, SNOW_SALT), 2, settings.surface.snowLineWavelength);
        this.rockNoise = new Fbm(Hashing.derive(seed, ROCK_SALT), 3, settings.surface.rockNoiseWavelength);
        this.rockPatchNoise = new Fbm(Hashing.derive(seed, ROCK_PATCH_SALT), 3, settings.surface.rockPatchWavelength);
        this.regions = new RegionCache<>(this::generateRegion, settings.general.regionCacheSize);
        FlowLayer flow = new FlowLayer(seed, settings, this::sampleDrainageHeight);
        this.flowRegions = new RegionCache<>(flow::compute, settings.general.regionCacheSize);
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

    /** Drainage of a logic region. Computed apart from {@link #region} because terrain does not depend on it yet. */
    public FlowRegion flowRegion(int regionX, int regionZ) {
        return flowRegions.get(regionX, regionZ);
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

    /**
     * Surface height without ridge texture, for drainage. Drainage windows span more regions than the region cache
     * holds, so the base comes straight from the continents instead of through the cache.
     */
    private double sampleDrainageHeight(double x, double z) {
        double base = continents.height(continents.value(x, z));
        return softCap(base + skeleton.coarseHeight(x, z, base - (settings.general.seaLevel - 1)));
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
     * Biome id at a block position: the column's surface biome, or a cave biome deep underground in some areas.
     * Memoized per column, because biomes are queried many times per column.
     */
    public String biomeAt(int x, int y, int z) {
        Column column = columns.get().column(this, x, z);
        if (y < column.height - settings.biomes.caveDepth && biomes.isCave(x, z)) {
            return biomes.caveBiome(climate.humidity(x, z));
        }
        return column.biome();
    }

    /** Surface biome id of a position. */
    public String sampleBiome(double x, double z) {
        double temperature = climate.temperature(x, z);
        return biomes.surfaceBiome(x, z, climate.zone(temperature), sampleSurfaceHeight(x, z), sampleCoastDistance(x, z),
                sampleSnowLine(x, z), climate.humidity(x, z), climate.nearZoneBorder(temperature));
    }

    /** Whether the ground around a position is flat enough for a village. */
    public boolean isVillageGround(double x, double z) {
        int radius = settings.general.villageRadius;
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += radius) {
            for (int dz = -radius; dz <= radius; dz += radius) {
                double height = sampleSurfaceHeight(x + dx, z + dz);
                low = Math.min(low, height);
                high = Math.max(high, height);
            }
        }
        return high - low <= settings.general.villageMaxRelief;
    }

    /** Climate zone name of a position. */
    public String sampleClimateZone(double x, double z) {
        return climate.zone(climate.temperature(x, z)).name();
    }

    /** Whether a block column has a snow surface, memoized
 per thread like {@link #surfaceHeight}. */
    public boolean isSnow(int x, int z) {
        return columns.get().column(this, x, z).snow();
    }

    /** Whether a block column has a bare rock surface, memoized per thread like {@link #surfaceHeight}. */
    public boolean isRock(int x, int z) {
        return columns.get().column(this, x, z).rock();
    }

    /** Whether a rock column uses the patch stone. */
    public boolean isRockPatch(int x, int z) {
        return rockPatchNoise.sample(x, z) > settings.surface.rockPatchThreshold;
    }

    /** Surface grade at a position as a fraction (1 = 100%). */
    public double sampleGrade(double x, double z) {
        double height = sampleSurfaceHeight(x, z);
        double dx = sampleSurfaceHeight(x + GRADE_STEP, z) - height;
        double dz = sampleSurfaceHeight(x, z + GRADE_STEP) - height;
        return Math.hypot(dx, dz) / GRADE_STEP;
    }

    /** Local snow line height. */
    public double sampleSnowLine(double x, double z) {
        WorldgenSettings.Surface surface = settings.surface;
        double line = climate.snowLine(climate.temperature(x, z));
        return settings.heightScale() * line * (1.0 + surface.snowLineVariation * snowLineNoise.sample(x, z));
    }

    /** Snow covers ground above the snow line, except faces too steep to hold it. */
    public boolean sampleSnow(double x, double z) {
        return isSnow(x, z, sampleSurfaceHeight(x, z), sampleGrade(x, z));
    }

    /** Bare rock covers steep ground, and gentler faces close to the snow line. */
    public boolean sampleRock(double x, double z) {
        return isRock(x, z, sampleSurfaceHeight(x, z), sampleGrade(x, z));
    }

    private boolean isSnow(double x, double z, double height, double grade) {
        return height >= settings.general.seaLevel - 1 && height >= sampleSnowLine(x, z)
                && grade * 100.0 < settings.surface.snowMaxSlope;
    }

    private boolean isRock(double x, double z, double height, double grade) {
        WorldgenSettings.Surface surface = settings.surface;
        if (height < settings.general.seaLevel - 1 + surface.rockMinAboveSea) {
            return false;
        }
        double slope = surface.rockSlope;
        double snowLine = sampleSnowLine(x, z);
        if (snowLine > 0.0) {
            // Blends on the height itself so the threshold changes without tracing a contour line
            double high = (height / snowLine - surface.bareRockLine) / (1.0 - surface.bareRockLine);
            slope += (surface.highRockSlope - surface.rockSlope) * Math.max(0.0, Math.min(1.0, high));
        }
        return grade * 100.0 > slope + surface.rockSlopeVariation * rockNoise.sample(x, z);
    }

    /**
     * Beaches run along every coast
 with a depth inland that varies along the coast, occasionally
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
        private double grade = Double.NaN;
        private int snow = -1;
        private int rock = -1;
        private String biome;

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

        private double grade() {
            if (Double.isNaN(grade)) {
                grade = owner.sampleGrade(x, z);
            }
            return grade;
        }

        String biome() {
            if (biome == null) {
                biome = owner.sampleBiome(x, z);
            }
            return biome;
        }

        boolean snow() {
            if (snow < 0) {
                snow = owner.isSnow(x, z, height, grade()) ? 1 : 0;
            }
            return snow == 1;
        }

        boolean rock() {
            if (rock < 0) {
                rock = owner.isRock(x, z, height, grade()) ? 1 : 0;
            }
            return rock == 1;
        }

        boolean beach() {
            if (beach < 0) {
                beach = owner.sampleBeach(x, z) ? 1 : 0;
            }
            return beach == 1;
        }
    }
}
