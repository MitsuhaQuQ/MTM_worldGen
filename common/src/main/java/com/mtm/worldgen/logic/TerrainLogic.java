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

    /** Share of lake cells around a position where a lake's water ends and its rim stands. */
    private static final double LAKE_EDGE = 0.25;

    private static final Map<WorldKey, TerrainLogic> WORLDS = new ConcurrentHashMap<>();

    private final WorldgenSettings settings;
    private final ContinentLayer continents;
    private final SkeletonLayer skeleton;
    private final ClimateLayer climate;
    private final CoastLayer coasts;
    private final BiomeLayer biomes;
    private final Fbm beachWidth;
    private final Fbm snowLineNoise;
    private final Fbm rockNoise;
    private final Fbm rockPatchNoise;
    private final RegionCache<LogicRegion> regions;
    private final RegionCache<FlowRegion> flowRegions;
    private final RegionCache<RiverRegion> riverRegions;
    private final ThreadLocal<LogicRegion> lastRegion = new ThreadLocal<>();
    private final ThreadLocal<FlowRegion> lastFlow = new ThreadLocal<>();
    private final ThreadLocal<RiverRegion> lastRivers = new ThreadLocal<>();
    private final ThreadLocal<ColumnCache> columns = ThreadLocal.withInitial(ColumnCache::new);

    public TerrainLogic(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.continents = new ContinentLayer(seed, settings);
        this.skeleton = new SkeletonLayer(seed, settings, continents);
        this.climate = new ClimateLayer(seed, settings);
        this.coasts = new CoastLayer(seed, settings);
        this.biomes = new BiomeLayer(seed, settings);
        this.beachWidth = new Fbm(Hashing.derive(seed, BEACH_SALT), 2, settings.beaches.widthWavelength);
        this.snowLineNoise = new Fbm(Hashing.derive(seed, SNOW_SALT), 2, settings.surface.snowLineWavelength);
        this.rockNoise = new Fbm(Hashing.derive(seed, ROCK_SALT), 3, settings.surface.rockNoiseWavelength);
        this.rockPatchNoise = new Fbm(Hashing.derive(seed, ROCK_PATCH_SALT), 3, settings.surface.rockPatchWavelength);
        this.regions = new RegionCache<>(this::generateRegion, settings.general.regionCacheSize);
        FlowLayer flow = new FlowLayer(seed, settings, this::sampleDrainageHeight, this::isUpland);
        this.flowRegions = new RegionCache<>(flow::compute, settings.general.regionCacheSize);
        RiverLayer rivers = new RiverLayer(seed, settings, this::flowRegion, this::sampleSmoothLand);
        this.riverRegions = new RegionCache<>(rivers::compute, settings.general.regionCacheSize);
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

    /** Refined river courses of a logic region. */
    public RiverRegion riverRegion(int regionX, int regionZ) {
        return riverRegions.get(regionX, regionZ);
    }

    /** Nearest river within rivers.queryReach of a position, or null. */
    public RiverRegion.Hit sampleRiver(double x, double z) {
        int size = settings.general.regionSize;
        int regionX = (int) Math.floorDiv((long) Math.floor(x), size);
        int regionZ = (int) Math.floorDiv((long) Math.floor(z), size);
        RiverRegion rivers = lastRivers.get();
        if (rivers == null || rivers.regionX() != regionX || rivers.regionZ() != regionZ) {
            rivers = riverRegion(regionX, regionZ);
            lastRivers.set(rivers);
        }
        return rivers.nearest(x, z);
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
        return isShore(x, z, CoastLayer.Type.SAND);
    }

    public boolean isGravelShore(int x, int z) {
        return isShore(x, z, CoastLayer.Type.GRAVEL);
    }

    public boolean isRockShore(int x, int z) {
        return isShore(x, z, CoastLayer.Type.ROCK) || isShore(x, z, CoastLayer.Type.CLIFF);
    }

    private boolean isShore(int x, int z, CoastLayer.Type type) {
        Column column = columns.get().column(this, x, z);
        return column.beach() && column.coastType() == type;
    }

    /**
     * Surface height at any position: the continent base interpolated from the 4x4 surrounding cell centers,
     * plus the terrain skeleton evaluated at the exact position, softly capped below the maximum height.
     */
    public double sampleSurfaceHeight(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double[] texture = new double[1];
        double smooth = base + skeletonHeight(x, z, base, texture);
        return carve(x, z, softCap(smooth), softCap(smooth + texture[0]));
    }

    /** Height of the continent layer alone, without the region cache: for sparse samples across a wide area. */
    /** Whether a position is on or around an island. */
    public boolean isIsland(double x, double z) {
        return continents.islandKind(x, z) != null;
    }

    public double sampleContinentHeight(double x, double z) {
        return continents.height(continents.value(x, z));
    }

    /**
     * Skeleton height over a continent base. Islands have none: a small dome rising from the sea could not carry the
     * hills and mountains of the open field, which would stand on it as spikes.
     */
    private double skeletonHeight(double x, double z, double base, double[] texture) {
        double clearance = continents.islandClearance(x, z);
        if (clearance <= 0.0) {
            if (texture != null) {
                texture[0] = 0.0;
            }
            return 0.0;
        }
        double height = clearance * skeleton.height(x, z, base - (settings.general.seaLevel - 1), texture);
        if (texture != null) {
            texture[0] *= clearance;
        }
        return height;
    }

    /** Surface height before water has shaped it. */
    public double sampleLandform(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double[] texture = new double[1];
        return softCap(base + skeletonHeight(x, z, base, texture) + texture[0]);
    }

    /** Surface height without ridge texture, which is the ground rivers run on. */
    public double sampleSmoothLand(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        return softCap(base + skeletonHeight(x, z, base, null));
    }

    /**
     * Shapes land around water. Ridge texture fades out toward rivers, which leaves them the smooth ground they
     * were routed on, with no spurs to cut through and no gullies to bridge. Valley walls then bound the land from above and below, flat on the valley floor and
     * spreading with distance, so land only changes where it would not leave a valley. Mountains that already fall
     * away toward a river stay as they are, and a river always runs in a channel.
     */
    private double carve(double x, double z, double smooth, double textured) {
        RiverRegion.Hit river = sampleRiver(x, z);
        double cliff = cliffHeight(x, z);
        double ground = cliff + (river == null ? textured : smooth + (textured - smooth) * river.texture());
        double land = river == null ? ground : carveValley(river, ground);
        double[] lake = sampleLake(x, z);
        if (lake == null) {
            return land;
        }
        // Lakes fill the land as it is, so shores follow the terrain. Inside one, valley banks give way to the basin,
        // and along its edge a rim closes any gap below the water. Land is never pulled down to a lake: rivers nearby
        // can run higher, and the ground beside them would drop away.
        double inside = smooth((lake[0] - LAKE_EDGE) / LAKE_EDGE);
        double shaped = land + (ground - land) * inside;
        double rim = smooth(lake[0] / LAKE_EDGE) * (1.0 - inside);
        double bank = lake[1] + settings.rivers.bankHeight;
        return shaped < bank ? shaped + (bank - shaped) * rim : shaped;
    }

    /** Extra land height of a sea cliff: a steep rise from the shore that falls away again behind it. */
    private double cliffHeight(double x, double z) {
        double strength = coasts.cliffStrength(x, z);
        double clearance = strength <= 0.0 ? 0.0 : continents.islandClearance(x, z);
        if (clearance <= 0.0) {
            return 0.0;
        }
        double distance = sampleCoastDistance(x, z);
        if (distance <= 0.0) {
            return 0.0;
        }
        WorldgenSettings.Beaches config = settings.beaches;
        double rise = smooth(distance / config.cliffRise);
        double behind = 1.0 - smooth((distance - config.cliffRise) / config.cliffFade);
        return clearance * strength * settings.heightScale() * config.cliffHeight * rise * behind;
    }

    /** The kind of coast at a position. Deserts have gravel shores, and islands decide for themselves. */
    public CoastLayer.Type sampleCoastType(double x, double z) {
        ContinentLayer.IslandKind island = continents.islandKind(x, z);
        if (island != null) {
            return island == ContinentLayer.IslandKind.ROCK ? CoastLayer.Type.ROCK : CoastLayer.Type.SAND;
        }
        double temperature = climate.temperature(x, z);
        if (climate.zone(temperature) == ClimateLayer.Zone.TROPICAL && climate.humidity(x, z) < settings.biomes.dryHumidity) {
            return CoastLayer.Type.GRAVEL;
        }
        return coasts.type(x, z);
    }

    private boolean isRockyCoast(double x, double z) {
        CoastLayer.Type type = sampleCoastType(x, z);
        return type == CoastLayer.Type.ROCK || type == CoastLayer.Type.CLIFF;
    }

    private double carveValley(RiverRegion.Hit river, double ground) {
        double half = river.halfWidth(settings.rivers.minChannelWidth);
        if (river.distance() < half) {
            double offset = river.distance() / half;
            return river.level() - river.depth() * (1.0 - offset * offset);
        }
        // The sea floor stays where it is, or banks would wall off the mouth
        double sea = settings.general.seaLevel - 1;
        double seaFloor = Math.min(ground, river.high());
        if (ground < sea) {
            return seaFloor;
        }
        double walled;
        if (river.low() > river.high()) {
            walled = 0.5 * (river.low() + river.high());
        } else {
            walled = Math.min(Math.max(ground, river.low()), river.high());
        }
        // Walls of a much higher or lower river nearby may disagree, but a river's banks always meet its own water
        double bank = river.level() + settings.rivers.bankHeight;
        walled = flatten(river, walled, bank);
        double land = bank + (walled - bank) * smooth(river.run() / settings.rivers.valleyBankBlend);
        return seaFloor + (land - seaFloor) * smooth((ground - sea) / settings.rivers.coastBlend);
    }

    /**
     * Lays low land near a river out as a plain just above it, raising what lies below the river and leaving hills and
     * mountains beside it alone.
     */
    private double flatten(RiverRegion.Hit river, double land, double bank) {
        double rise = land - bank;
        if (rise <= 0.0) {
            return land + (bank - land) * smooth(2.0 * river.floodplain());
        }
        double low = 1.0 - smooth(rise / settings.rivers.floodplainRelief);
        double plain = bank + Math.min(rise, settings.rivers.floodplainRise);
        return land + (plain - land) * smooth(2.0 * river.floodplain()) * low;
    }

    private static double smooth(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /** Water level of the lake at a position, or NaN away from lakes. */
    public double sampleLakeLevel(double x, double z) {
        double[] lake = sampleLake(x, z);
        return lake != null && lake[0] > LAKE_EDGE ? lake[1] : Double.NaN;
    }

    /** Whether a position lies in a river channel, rather than a lake the river runs through. */
    private boolean isRiverChannel(double x, double z) {
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river == null || river.distance() >= river.halfWidth(settings.rivers.minChannelWidth)) {
            return false;
        }
        double[] lake = sampleLake(x, z);
        return lake == null || lake[0] <= LAKE_EDGE;
    }

    /** Water surface of a column, or NaN where it holds no river or lake. */
    public double sampleWaterLevel(double x, double z) {
        double[] lake = sampleLake(x, z);
        if (lake != null && lake[0] > LAKE_EDGE && sampleSurfaceHeight(x, z) < lake[1]) {
            return lake[1];
        }
        RiverRegion.Hit river = sampleRiver(x, z);
        return river != null && river.distance() < river.halfWidth(settings.rivers.minChannelWidth) ? river.level() : Double.NaN;
    }

    /**
     * Lake influence at a position, as {share of lake among the four nearest routing cells, blended smoothly, and
     * the lake's water level}, or null away from lakes.
     */
    private double[] sampleLake(double x, double z) {
        int size = settings.rivers.flowCellSize;
        double u = x / size - 0.5;
        double v = z / size - 0.5;
        int cellX = (int) Math.floor(u);
        int cellZ = (int) Math.floor(v);
        double tx = smooth(u - cellX);
        double tz = smooth(v - cellZ);
        double share = 0.0;
        double levelSum = 0.0;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                int cells = settings.general.regionSize / size;
                FlowRegion flow = flowRegionAt(Math.floorDiv(cellX + dx, cells), Math.floorDiv(cellZ + dz, cells));
                int lx = Math.floorMod(cellX + dx, cells);
                int lz = Math.floorMod(cellZ + dz, cells);
                if (flow.isLake(lx, lz)) {
                    double weight = (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz);
                    share += weight;
                    levelSum += weight * flow.lakeLevel(lx, lz);
                }
            }
        }
        return share > 0.0 ? new double[]{share, levelSum / share} : null;
    }

    private FlowRegion flowRegionAt(int regionX, int regionZ) {
        FlowRegion flow = lastFlow.get();
        if (flow == null || flow.regionX() != regionX || flow.regionZ() != regionZ) {
            flow = flowRegions.get(regionX, regionZ);
            lastFlow.set(flow);
        }
        return flow;
    }

    private boolean isUpland(double x, double z) {
        double base = continents.height(continents.value(x, z));
        return skeleton.isUpland(x, z, base - (settings.general.seaLevel - 1));
    }

    /**
     * Smooth land for drainage. Drainage windows span more regions than the region cache holds, so the base comes
     * straight from the continents instead of through the cache.
     */
    private double sampleDrainageHeight(double x, double z) {
        double base = continents.height(continents.value(x, z));
        return softCap(base + skeletonHeight(x, z, base, null));
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
                sampleSnowLine(x, z), climate.humidity(x, z), isRiverChannel(x, z), isRockyCoast(x, z), continents.islandKind(x, z),
                climate.nearZoneBorder(temperature));
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
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cellX = dx * settings.spawn.searchStep;
                    int cellZ = dz * settings.spawn.searchStep;
                    int half = settings.general.cellSize / 2;
                    int x = cellX * settings.general.cellSize + half;
                    int z = cellZ * settings.general.cellSize + half;
                    // Most far samples are open sea, and this rules them out without touching the region cache
                    if (sampleContinentHeight(x, z) >= sea && isSpawnCell(cellX, cellZ, sea)
                            && sampleSurfaceHeight(x, z) >= sea + settings.spawn.minAboveSea && Double.isNaN(sampleWaterLevel(x, z))) {
                        return new BlockColumn(x, z);
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
                    if (distance < bestDistance && sampleLandform(x, z) >= minHeight) {
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
        return sampleLandform((cellX + 0.5) * size, (cellZ + 0.5) * size);
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
        private CoastLayer.Type coastType;

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

        CoastLayer.Type coastType() {
            if (coastType == null) {
                coastType = owner.sampleCoastType(x, z);
            }
            return coastType;
        }

        boolean beach() {
            if (beach < 0) {
                beach = owner.sampleBeach(x, z) ? 1 : 0;
            }
            return beach == 1;
        }
    }
}
