package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

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
    private static final long VALLEY_WALL_SALT = 0x57414C4CL;
    /** Block spacing of the finite difference used for the surface grade. */
    /** Marks the chunks of this version, so that chunks of another one can be told apart after an update. */
    public static final int ALGORITHM_VERSION = 16;
    private static final long WORLD_SALT = 0x574F524C44L;
    private static final double GRADE_STEP = 2.0;
    private static final double[] NO_OVERHANG = new double[0];
    /** How quickly banks around foreign water take hold as a blend gets stronger. */
    private static final double BANK_HOLD = 4.0;

    private static final int CHUNK_SIZE = 16;
    /** Horizontal spacing of the corners the terrain density is evaluated at. */
    private static final int NOISE_CELL_WIDTH = 4;


    /** Sink share at which an inland lake's basin and rim shape the land in full, where the basin starts to be dug. */
    private static final double SINK_RIM_FADE = 0.2;
    /** How far off the coast /mwg locate ocean looks, in blocks, so it finds open water rather than the shallows. */
    private static final double OCEAN_OFFSHORE = 64.0;
    private static final int FIELD_HEIGHT = 0;
    private static final int FIELD_COAST_DISTANCE = 1;

    /** Share of lake cells around a position where a lake's water ends and its rim stands. */
    private static final double LAKE_EDGE = 0.25;
    /** Share of its growth from which a river starting at its source holds water. Erosion holds the same stretch to it. */
    static final double SOURCE_WATER = 0.15;
    /**
     * Creeks: the share of upland below which they do not run (erosion fades out toward plains with it, and takes as
     * much again to reach them fully), how far past their edge the banks their level is taken from reach, and how much
     * more water than they start with they need to be full grown.
     */
    private static final double CREEK_SHARE = 0.3;
    private static final double CREEK_BANK = 2.0;
    private static final double CREEK_GROWTH = 1.0;
    /** Share of the desert's climate by which creeks have given way, when dunes are a few tenths of a block high. */
    private static final double DESERT_CREEK_FADE = 0.05;
    /** Spread of the noise that mixes gravel and sand where a bar changes from one to the other. */
    private static final double GRAIN_PATCHES = 0.6;
    /** Blocks from a creek's course over which cliff bands come back. */
    private static final double CREEK_CLIFF_FADE = 48.0;
    /** Spread of the noise over which a stretch of river terraces fades in and out. */
    private static final double TERRACE_EDGE = 0.3;
    /** Share of upland at which river terraces reach their full height. */
    private static final double TERRACE_UPLAND = 0.5;

    private static final Map<WorldKey, TerrainLogic> WORLDS = new ConcurrentHashMap<>();

    private final WorldgenSettings settings;
    private final long seed;
    private final ContinentLayer continents;
    private final SkeletonLayer skeleton;
    private final SeaStackLayer stacks;
    private final ClimateLayer climate;
    private final CoastLayer coasts;
    private final BiomeLayer biomes;
    private final OverhangLayer overhangs;
    private final SinkholeLayer sinkholes;
    private final DesertLayer deserts;
    private volatile ForeignMap foreign = ForeignMap.EMPTY;
    private final long worldId;
    private final Fbm sinkShore;
    private final Fbm mouthSandNoise;
    private final Fbm barNoise;
    private final Fbm grainNoise;
    private final Fbm duneNoise;
    private final Fbm mouthClayNoise;
    private final Fbm beachWidth;
    private final Fbm snowLineNoise;
    private final Fbm rockNoise;
    private final Fbm valleyWallNoise;
    private final Fbm terraceNoise;
    private final Fbm terraceHeightNoise;
    private final Fbm rockPatchNoise;
    private final Fbm strataNoise;
    private final long strataSeed;
    private final RegionCache<LogicRegion> regions;
    private final RegionCache<FlowRegion> flowRegions;
    private final RegionCache<LakeLayer.LakeRegion> lakeRegions;
    private final RegionCache<RiverRegion> riverRegions;
    /** Rivers as the seed lays them out, without what foreign chunks do to them, for erosion to drain into. */
    private final RegionCache<RiverRegion> naturalRiverRegions;
    /** Rivers with their water lowered onto the ground erosion left, where it lies lower than they were routed. */
    private final RegionCache<RiverRegion> fittedRiverRegions;
    /** First fitting pass, used only for the valleys of wider rivers when fitting small tributaries. */
    private final RegionCache<RiverRegion> valleyRiverRegions;
    private final ErosionLayer erosion;
    private final ThreadLocal<LogicRegion> lastRegion = new ThreadLocal<>();
    private final ThreadLocal<FlowRegion> lastFlow = new ThreadLocal<>();
    private final ThreadLocal<LakeLayer.LakeRegion> lastLakes = new ThreadLocal<>();
    private final ThreadLocal<RiverRegion> lastRivers = new ThreadLocal<>();
    private final ThreadLocal<RiverMemo> riverMemo = ThreadLocal.withInitial(RiverMemo::new);
    private final ThreadLocal<RiverMemo> naturalMemo = ThreadLocal.withInitial(RiverMemo::new);
    private final ThreadLocal<ColumnCache> columns = ThreadLocal.withInitial(ColumnCache::new);

    /** Terrain logic of a world, working with its settings as general.logicRange resolves them. */
    public TerrainLogic(long seed, WorldgenSettings settings) {
        this(settings.resolved(), seed);
    }

    private TerrainLogic(WorldgenSettings settings, long seed) {
        this.settings = settings;
        this.seed = seed;
        this.continents = new ContinentLayer(seed, settings);
        this.climate = new ClimateLayer(seed, settings);
        this.biomes = new BiomeLayer(seed, settings);
        this.skeleton = new SkeletonLayer(seed, settings, continents, this::desertClimate);
        this.coasts = new CoastLayer(seed, settings);
        this.worldId = Hashing.derive(seed, WORLD_SALT);
        this.overhangs = new OverhangLayer(seed, settings);
        this.sinkholes = new SinkholeLayer(seed, settings, this);
        this.deserts = new DesertLayer(seed, settings, this);
        this.sinkShore = new Fbm(Hashing.derive(seed, VALLEY_WALL_SALT + 1), 3, settings.rivers.sinkWavelength);
        this.mouthSandNoise = new Fbm(Hashing.derive(seed, BEACH_SALT + 1), 3, settings.rivers.mouthSandWavelength);
        this.barNoise = new Fbm(Hashing.derive(seed, BEACH_SALT + 4), 2, settings.rivers.barWavelength);
        this.grainNoise = new Fbm(Hashing.derive(seed, BEACH_SALT + 5), 2, 0.25 * settings.rivers.barWavelength);
        this.mouthClayNoise = new Fbm(Hashing.derive(seed, BEACH_SALT + 3), 2, settings.rivers.mouthClayWavelength);
        this.duneNoise = new Fbm(Hashing.derive(seed, BEACH_SALT + 2), 2, settings.rivers.duneWavelength);
        this.beachWidth = new Fbm(Hashing.derive(seed, BEACH_SALT), 2, settings.beaches.widthWavelength);
        this.snowLineNoise = new Fbm(Hashing.derive(seed, SNOW_SALT), 2, settings.surface.snowLineWavelength);
        this.stacks = new SeaStackLayer(seed, settings, this);
        this.rockNoise = new Fbm(Hashing.derive(seed, ROCK_SALT), 3, settings.surface.rockNoiseWavelength);
        this.valleyWallNoise = new Fbm(Hashing.derive(seed, VALLEY_WALL_SALT), 3, settings.rivers.valleyWallWavelength);
        this.terraceNoise = new Fbm(Hashing.derive(seed, VALLEY_WALL_SALT + 2), 2, settings.rivers.terraceWavelength);
        this.terraceHeightNoise = new Fbm(Hashing.derive(seed, VALLEY_WALL_SALT + 3), 2, settings.rivers.terraceWavelength);
        this.rockPatchNoise = new Fbm(Hashing.derive(seed, ROCK_PATCH_SALT), 3, settings.surface.rockPatchWavelength);
        this.strataNoise = new Fbm(Hashing.derive(seed, ROCK_PATCH_SALT + 1), 2, settings.surface.strataWavelength);
        this.strataSeed = Hashing.derive(seed, ROCK_PATCH_SALT + 2);
        this.regions = new RegionCache<>(this::generateRegion, settings.general.regionCacheSize);
        FlowLayer flow = new FlowLayer(seed, settings, this::sampleDrainageHeight, this::isUpland, this::canSink);
        this.flowRegions = new RegionCache<>(flow::compute, settings.general.regionCacheSize);
        LakeLayer lakes = new LakeLayer(settings, this::flowRegion, this::sampleSmoothLand);
        this.lakeRegions = new RegionCache<>(lakes::compute, settings.general.regionCacheSize);
        RiverLayer rivers = new RiverLayer(seed, settings, this::flowRegion, this::sampleSmoothLand, this::isRockyCoast, (x, z) -> foreign.cuts(x, z));
        this.riverRegions = new RegionCache<>(rivers::compute, settings.general.regionCacheSize);
        RiverLayer naturalRivers = new RiverLayer(seed, settings, this::flowRegion, this::sampleSmoothLand, this::isRockyCoast, (x, z) -> false);
        this.naturalRiverRegions = new RegionCache<>(naturalRivers::compute, settings.general.regionCacheSize);
        this.valleyRiverRegions = new RegionCache<>((x, z) -> riverRegion(x, z).lowered(
                this::riverGround, this::sampleLakeLevel, null), settings.general.regionCacheSize);
        this.fittedRiverRegions = new RegionCache<>(this::fitRivers, settings.general.regionCacheSize);
        this.erosion = new ErosionLayer(seed, settings, new ErosionLayer.HeightSampler() {
            @Override
            public double height(double x, double z) {
                return sampleSmoothLand(x, z);
            }

            @Override
            public double riverLevel(double x, double z) {
                RiverRegion.Hit river = naturalRiver(x, z);
                return river != null && river.strength() >= SOURCE_WATER
                        && river.distance() <= river.halfWidth(settings.rivers.minChannelWidth) + 0.5 * settings.erosion.cellSize
                        ? river.level() : Double.NaN;
            }

            @Override
            public double lakeLevel(double x, double z) {
                return sampleLakeLevel(x, z);
            }

            @Override
            public Runnable prepare(double minX, double minZ, double maxX, double maxZ) {
                // Interpolation reads cells a little past the area, and lakes the drainage around it, so hold the
                // regions around it too. Rivers of the area itself hold everything that reaches into it
                int size = settings.general.regionSize;
                int fromX = Math.floorDiv((int) Math.floor(minX), size);
                int toX = Math.floorDiv((int) Math.ceil(maxX), size);
                int fromZ = Math.floorDiv((int) Math.floor(minZ), size);
                int toZ = Math.floorDiv((int) Math.ceil(maxZ), size);
                RegionCache<RiverRegion> rivers = foreign.isEmpty() ? riverRegions : naturalRiverRegions;
                for (int regionX = fromX - 1; regionX <= toX + 1; regionX++) {
                    for (int regionZ = fromZ - 1; regionZ <= toZ + 1; regionZ++) {
                        regions.hold(regionX, regionZ);
                        flowRegions.hold(regionX, regionZ);
                        lakeRegions.hold(regionX, regionZ);
                    }
                }
                for (int regionX = fromX; regionX <= toX; regionX++) {
                    for (int regionZ = fromZ; regionZ <= toZ; regionZ++) {
                        rivers.hold(regionX, regionZ);
                    }
                }
                return () -> {
                    for (int regionX = fromX - 1; regionX <= toX + 1; regionX++) {
                        for (int regionZ = fromZ - 1; regionZ <= toZ + 1; regionZ++) {
                            regions.release(regionX, regionZ);
                            flowRegions.release(regionX, regionZ);
                            lakeRegions.release(regionX, regionZ);
                        }
                    }
                    for (int regionX = fromX; regionX <= toX; regionX++) {
                        for (int regionZ = fromZ; regionZ <= toZ; regionZ++) {
                            rivers.release(regionX, regionZ);
                        }
                    }
                };
            }
        });
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

    /**
     * Stands for the world. It comes from the seed alone: the settings of a world are locked, but every update adds to
     * them, and their text changing would make all its own chunks look foreign.
     */
    public long worldId() {
        return worldId;
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

    /** How much of its warp erosion is read through at a position: none in the channels it was held to, all a little away. */
    private double erosionWarpShare(double x, double z) {
        RiverRegion.Hit river = naturalRiver(x, z);
        if (river == null) {
            return 1.0;
        }
        double clear = river.distance() - river.halfWidth(settings.rivers.minChannelWidth);
        return smooth(clear / (2.0 * settings.erosion.warp + settings.erosion.cellSize));
    }

    /** Rivers of a logic region as the seed lays them out, the same as {@link #riverRegion} without foreign chunks. */
    private RiverRegion naturalRiverRegion(int regionX, int regionZ) {
        return foreign.isEmpty() ? riverRegion(regionX, regionZ) : naturalRiverRegions.get(regionX, regionZ);
    }

    /** Nearest river of {@link #naturalRiverRegion} within rivers.queryReach of a position, or null. */
    private RiverRegion.Hit naturalRiver(double x, double z) {
        int size = settings.general.regionSize;
        RiverRegion rivers = naturalRiverRegion((int) Math.floorDiv((long) Math.floor(x), size), (int) Math.floorDiv((long) Math.floor(z), size));
        return naturalMemo.get().nearest(rivers, x, z);
    }

    /**
     * The last river query of a thread. A column asks for its river several times over, for erosion's warp, for the
     * valley, for creeks and for its water, and each query searches every segment within reach.
     */
    private static final class RiverMemo {
        private RiverRegion rivers;
        private double x;
        private double z;
        private RiverRegion.Hit hit;

        RiverRegion.Hit nearest(RiverRegion rivers, double x, double z) {
            if (rivers != this.rivers || x != this.x || z != this.z) {
                this.hit = rivers.nearest(x, z);
                this.rivers = rivers;
                this.x = x;
                this.z = z;
            }
            return hit;
        }
    }

    /** Nearest river within rivers.queryReach of a position, or null. */
    public RiverRegion.Hit sampleRiver(double x, double z) {
        return riverMemo.get().nearest(riversAt(x, z), x, z);
    }

    /** Rivers of the region a position lies in, as {@link #sampleRiver} queries them. */
    private RiverRegion riversAt(double x, double z) {
        int size = settings.general.regionSize;
        int regionX = (int) Math.floorDiv((long) Math.floor(x), size);
        int regionZ = (int) Math.floorDiv((long) Math.floor(z), size);
        RiverRegion rivers = lastRivers.get();
        if (rivers == null || rivers.regionX() != regionX || rivers.regionZ() != regionZ) {
            rivers = fittedRiverRegions.get(regionX, regionZ);
            lastRivers.set(rivers);
        }
        return rivers;
    }

    /**
     * Rivers of a region with their water no higher than the land erosion left beside them. Rivers are routed on the
     * smooth land, and erosion is held to them, but between its cells and near sources the ground can lie lower; the
     * water then follows it down rather than standing on banks raised out of the slope. Pools above waterfalls stay
     * level, at the lowest ground under either end. Each point's level depends on that point alone, so neighboring regions agree.
     */
    private RiverRegion fitRivers(int regionX, int regionZ) {
        if (!settings.erosion.enabled) {
            return riverRegion(regionX, regionZ).withOxbowBanks(this::sampleOxbowBank);
        }
        return riverRegion(regionX, regionZ).lowered((x, z) -> {
            double land = riverGround(x, z);
            RiverRegion.Hit natural = naturalRiver(x, z);
            if (natural != null && natural.width() < settings.rivers.tributaryWidth) {
                int size = settings.general.regionSize;
                RiverRegion wider = valleyRiverRegions.get((int) Math.floor(x / size), (int) Math.floor(z / size));
                RiverRegion.Hit valley = wider.nearest(x, z, settings.rivers.tributaryWidth);
                if (valley != null) {
                    // Fit onto the valley that already exists, not the original hillside above it. Otherwise a
                    // tributary's pools and banks hold up the side of a lower trunk or lake as a broad terrace.
                    land = Math.min(land, Math.max(valley.level(), carveValley(x, z, valley, land)));
                }
            }
            return land;
        }, this::sampleLakeLevel, this::sampleOxbowBank);
    }

    /** Ground used by the independent first fitting pass; lakes hold rivers at their surface rather than their bed. */
    private double riverGround(double x, double z) {
        double[] lake = sampleLake(x, z);
        if (lake != null && lake[0] > LAKE_EDGE) {
            return lake[1];
        }
        double land = sampleLandform(x, z);
        return lake == null ? land : Math.max(land, lake[1]);
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
                highest = Math.max(highest, highestSolid(chunkX * CHUNK_SIZE + dx, chunkZ * CHUNK_SIZE + dz));
            }
        }
        return (int) Math.ceil(highest) + settings.general.structureTerrainMargin;
    }

    /**
     * Keeps eroded regions in files under a directory from now on, normally in the world's folder. Worlds with the
     * same seed and settings share this logic, and their files would be the same, so the last world to set it wins.
     */
    public void useCacheDirectory(Path directory) {
        erosion.setStore(directory == null ? null : new ErosionStore(directory, seed, settings.toJson(), ALGORITHM_VERSION));
    }

    /**
     * Computes everything a logic region needs ahead of time: its terrain, drainage, rivers and erosion. Only the
     * erosion outlasts the memory cache, through the cache directory; the rest is quick to compute again.
     */
    public void precomputeRegion(int regionX, int regionZ) {
        if (settings.erosion.enabled) {
            erosion.precompute(regionX, regionZ);
        }
        riverRegion(regionX, regionZ);
    }

    /** Takes on the foreign chunks of a world. Called before it generates anything. */
    public void setForeign(ForeignMap map) {
        map.bind(this::unblendedHeight);
        foreign = map;
        riverRegions.clear();
        fittedRiverRegions.clear();
        valleyRiverRegions.clear();
    }

    public ForeignMap foreign() {
        return foreign;
    }

    /** Whether a blend reaches a position, which leaves it without three-dimensional rock. */
    private boolean foreignNear(double x, double z) {
        return !foreign.isEmpty() && foreign.influence(x, z, sampleSmoothLand(x, z)) != null;
    }

    /** Land as it closes in on foreign chunks: smooth land heads for their edge, and any detail on it fades out. */
    private double blendForeign(double x, double z, double land) {
        ForeignMap map = foreign;
        if (map.isEmpty()) {
            return land;
        }
        double smooth = sampleSmoothLand(x, z);
        ForeignMap.Influence influence = map.influence(x, z, smooth);
        if (influence == null) {
            return land;
        }
        if (influence.upgraded()) {
            return upgradedBlend(x, z, land, influence);
        }
        double share = foreignShare(x, z, influence);
        double blended = closeIn(smooth, land, influence.ground(), share);
        double[] held = heldWater(x, z, influence);
        if (held != null) {
            return dammed(land, blended, held, influence);
        }
        double pond = influence.wetLevel();
        if (Double.isNaN(pond)) {
            // A foreign sea below the world's own needs a bank, or the sea here would stand against it as a wall
            return influence.water() < seaSurface() - 1.0 ? bankedUp(blended, seaSurface() + 1.0, smooth(BANK_HOLD * share)) : blended;
        }
        // A lake cut off at the edge of a map would pour out over lower land, as real flowing water. So banks go all
        // the way around it, measured from any water on the edge, not just the nearest piece of edge, and what the
        // edge has left open becomes a pool held in by them. Both fade out, or they would stand as steps
        double reach = settings.foreign.pondReach;
        double wet = influence.wetDistance();
        double banked = bankedUp(blended, pond + 1.0, 1.0 - smooth((wet - reach) / ((ForeignMap.WET_REACH - 1.0) * reach)));
        double dug = smooth((reach - wet) / (0.5 * reach));
        return banked + (Math.min(banked, pond - 1.0) - banked) * dug;
    }

    /**
     * Land around chunks of an older MTM. Both sides came from the same seed, so instead of heading for the edge, the
     * land keeps all its shape and is only shifted by how much the older ground differs, which is nothing wherever the
     * algorithm has not changed. Rivers run on into the old chunks where those hold water, and are shifted with their
     * valleys; where they are dry, rivers end before them as before any foreign chunk. Water on the edge that this side
     * has nothing of is banked in.
     */
    private double upgradedBlend(double x, double z, double land, ForeignMap.Influence influence) {
        double shifted = land + influence.offset() * influence.share();
        double pond = influence.wetLevel();
        if (Double.isNaN(pond) || hasOwnWater(x, z, settings.foreign.pondReach)) {
            return shifted;
        }
        double reach = settings.foreign.pondReach;
        return bankedUp(shifted, pond + 1.0, 1.0 - smooth((influence.wetDistance() - reach) / ((ForeignMap.WET_REACH - 1.0) * reach)));
    }

    /** Whether this world has a river or lake within a distance of a position, found without its surface height. */
    private boolean hasOwnWater(double x, double z, double reach) {
        double[] lake = sampleLake(x, z);
        if (lake != null && lake[0] > 0.0) {
            return true;
        }
        RiverRegion.Hit river = sampleRiver(x, z);
        return river != null && river.distance() < river.halfWidth(settings.rivers.minChannelWidth) + reach;
    }

    /** Height of this world at a position as if no foreign chunks were near: what upgraded chunks are measured against. */
    private double unblendedHeight(double x, double z) {
        return sinkholes.lower(x, z, stacks.raise(x, z, sampleShapedLand(x, z)));
    }

    /**
     * Water of this world that a blend would leave standing above the land, as its level and how close it is, from 0
     * to 1, or null. Rivers and lakes on high ground next to lower foreign land would lose their banks to the blend and
     * pour out over the slope. Water that meets a foreign sea at its own level needs nothing.
     */
    private double[] heldWater(double x, double z, ForeignMap.Influence influence) {
        double[] lake = sampleLake(x, z);
        RiverRegion.Hit river = sampleRiver(x, z);
        double level;
        double near;
        if (lake != null && lake[0] > LAKE_EDGE) {
            level = lake[1];
            near = 1.0;
        } else if (river != null) {
            level = river.level();
            double half = river.halfWidth(settings.rivers.minChannelWidth);
            near = 1.0 - smooth((river.distance() - half) / settings.foreign.damMargin);
        } else {
            return null;
        }
        boolean dry = Double.isNaN(influence.water()) || influence.water() == ForeignMap.NO_WATER;
        if (influence.upgraded()) {
            // The land keeps its banks, and rivers either run on into the water of the old chunks or end before them
            return null;
        }
        double edgeTop = dry ? influence.ground() : Math.max(influence.ground(), influence.water());
        return near > 0.0 && level > edgeTop + 1.0 ? new double[]{level, near} : null;
    }

    /** Distance from the foreign edge at which a dam reaches the top of a water level: the water stays behind it. */
    private double damFoot(double level, ForeignMap.Influence influence) {
        return (level + 1.0 - influence.ground()) / settings.foreign.damGrade;
    }

    /**
     * Land between a foreign edge and water that stands higher: a slope up from the edge to a crest above the water,
     * and behind that the land as it was, so the water lies level. The closer land is to the water, the more it holds.
     */
    private double dammed(double land, double blended, double[] held, ForeignMap.Influence influence) {
        double crest = held[0] + 1.0;
        double foot = damFoot(held[0], influence);
        double distance = influence.distance();
        if (distance >= foot) {
            return blended + (land - blended) * held[1];
        }
        double dam = influence.ground() + (crest - influence.ground()) * (distance / foot);
        return blended + (Math.max(blended, dam) - blended) * held[1];
    }

    /** Land raised toward a bank, by the given share of the way. */
    private static double bankedUp(double land, double bank, double hold) {
        return land < bank ? land + (bank - land) * hold : land;
    }

    /**
     * Share of a blend at a position. Rivers passing through keep their ground, so the land gives way to them, but
     * only out in the blend: at the seam the land has to meet the foreign edge whatever runs nearby, or it stops
     * short of it and leaves the edge standing as a wall. River fades reach hundreds of blocks, so this happens a lot.
     */
    private double foreignShare(double x, double z, ForeignMap.Influence influence) {
        RiverRegion.Hit river = sampleRiver(x, z);
        double share = influence.share();
        return river == null ? share : share * (river.texture() + (1.0 - river.texture()) * share * share);
    }

    private static double closeIn(double smooth, double value, double target, double share) {
        double head = smooth + (target - smooth) * share;
        return head + (value - smooth) * (1.0 - share);
    }

    /** Surface height at a block column, memoized per thread for repeated queries of the same column. */
    public double surfaceHeight(int x, int z) {
        return columns.get().column(this, x, z).height;
    }

    /**
     * Height the density of a block compares against: the surface, moved along the slope on faces with overhangs.
     * {@link #surfaceHeight} remains the height map, and this stays out of everything but the blocks themselves.
     */
    public double solidHeight(int x, int y, int z) {
        return columns.get().column(this, x, z).solidHeight(y);
    }

    /** Highest value of {@link #solidHeight} in a block column. */
    public double highestSolid(int x, int z) {
        return columns.get().column(this, x, z).highestSolid();
    }

    /** The sinkhole of a site cell as x, floor height, z, or null. */
    public double[] sinkholePit(int cellX, int cellZ) {
        return sinkholes.pit(cellX, cellZ);
    }

    /** Whether a position lies on the walls or floor of a sinkhole. */
    boolean inSinkhole(double x, double z) {
        return sinkholes.contains(x, z);
    }

    /** Whether a block column has overhangs. */
    public boolean hasOverhangs(int x, int z) {
        return columns.get().column(this, x, z).overhangProfile() != NO_OVERHANG;
    }

    /**
     * Surface heights along the slope through a column, downhill first, or {@link #NO_OVERHANG}. Keeping them spares
     * density, which asks for many heights in a column, from computing the surface each time. Water rules them out:
     * valley walls shape the land near it, and the ground must not leave those to stand over a river.
     */
    private double[] sampleOverhangProfile(int x, int z, double height) {
        double patch = foreignNear(x, z) ? 0.0 : overhangs.patch(x, z, height);
        if (patch <= 0.0) {
            return NO_OVERHANG;
        }
        double dx = sampleSurfaceHeight(x + GRADE_STEP, z) - height;
        double dz = sampleSurfaceHeight(x, z + GRADE_STEP) - height;
        double rise = Math.hypot(dx, dz);
        RiverRegion.Hit river = sampleRiver(x, z);
        double reach = settings.overhangs.reach * patch * overhangs.slope(rise / GRADE_STEP)
                * (river == null ? 1.0 : river.texture());
        if (reach < 1.0 || !Double.isNaN(sampleLakeLevel(x, z))) {
            return NO_OVERHANG;
        }
        double[] profile = new double[OverhangLayer.PROFILE_SIZE];
        int middle = profile.length / 2;
        for (int i = 0; i < profile.length; i++) {
            double offset = reach * (i - middle) / middle;
            profile[i] = i == middle ? height : sampleSurfaceHeight(x + dx / rise * offset, z + dz / rise * offset);
        }
        return profile;
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
        return blendForeign(x, z, sinkholes.lower(x, z, stacks.raise(x, z, sampleShapedLand(x, z))));
    }

    /**
     * Surface height for vanilla's preliminary surface, without sea stacks, which stand too thin for its coarse grid.
     */
    public double preliminaryHeight(int x, int z) {
        return blendForeign(x, z, columns.get().column(this, x, z).shaped);
    }

    /** Whether desert lowland far from the sea can hold an inland lake that rivers end in. */
    private boolean canSink(double x, double z) {
        double temperature = climate.temperature(x, z);
        return climate.zone(temperature) == ClimateLayer.Zone.TROPICAL && !climate.nearZoneBorder(temperature)
                && climate.humidity(x, z) < settings.biomes.dryHumidity && !isUpland(x, z)
                && sampleCoastDistance(x, z) > settings.rivers.sinkMinCoastDistance;
    }

    /**
     * Height of the ground a sinkhole would open in at a position, or NaN if it cannot open there: it needs fairly
     * gentle, dry upland well above the sea.
     */
    double sinkholeGround(double x, double z) {
        WorldgenSettings.Sinkholes config = settings.sinkholes;
        double height = sampleShapedLand(x, z);
        if (height < settings.general.seaLevel + config.minAboveSea || !isUpland(x, z) || isNearWater(x, z)) {
            return Double.NaN;
        }
        double grade = Math.hypot(sampleShapedLand(x + config.radiusMax, z) - sampleShapedLand(x - config.radiusMax, z),
                sampleShapedLand(x, z + config.radiusMax) - sampleShapedLand(x, z - config.radiusMax)) / (2.0 * config.radiusMax);
        return grade > config.maxGrade ? Double.NaN : height;
    }

    /** Whether water shapes the land at a position, which keeps sinkholes away. */
    private boolean isNearWater(double x, double z) {
        RiverRegion.Hit river = sampleRiver(x, z);
        return river != null && river.texture() < 1.0 || !Double.isNaN(sampleLakeLevel(x, z));
    }

    /** Surface height before sea stacks stand on it. */
    private double sampleShapedLand(double x, double z) {
        double land = sampleCarvedLand(x, z);
        Creek creek = creekAt(x, z);
        if (creek == null) {
            return land;
        }
        double distance = creek.hit.distance();
        if (distance >= creek.half) {
            // The walls of the small valley the creek has cut, rising from its banks until they meet the slope
            return Math.min(land, creek.level() + (distance - creek.half) * settings.erosion.creekValleyGrade);
        }
        double offset = distance / creek.half;
        return Math.min(land, creek.level() - settings.erosion.creekDepth * creek.grown * (1.0 - offset * offset));
    }

    /** Surface height shaped by rivers and lakes, with dunes and oases in the desert, before creeks cut into it. */
    private double sampleCarvedLand(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double[] texture = new double[1];
        double smooth = base + skeletonHeight(x, z, base, texture);
        double ground = softCap(x, z, base, smooth);
        DesertLayer.Site oasis = deserts.oasis(x, z);
        double carved = carve(x, z, ground, softCap(x, z, base, smooth + texture[0]), duneHeight(x, z, ground, base, oasis));
        return oasis == null ? carved : deserts.pond(oasis, x, z, carved);
    }

    /** Surface height shaped by rivers and lakes alone, as an oasis finds the ground before it lies there. */
    private double sampleRiverShapedLand(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double[] texture = new double[1];
        double smooth = base + skeletonHeight(x, z, base, texture);
        return carve(x, z, softCap(x, z, base, smooth), softCap(x, z, base, smooth + texture[0]), 0.0);
    }

    /** Height of the dunes at a position, where they grow in the desert, and none around lakes and oases. */
    private double duneHeight(double x, double z, double ground, double base, DesertLayer.Site oasis) {
        WorldgenSettings.Deserts config = settings.deserts;
        if (!config.dunes || config.duneHeight <= 0.0) {
            return 0.0;
        }
        double share = deserts.fieldShare(x, z);
        if (share <= 0.0) {
            return 0.0;
        }
        share *= desertShare(x, z, ground, base);
        if (share <= 0.0) {
            return 0.0;
        }
        // Lakes fill the land as it is, and dunes in one would stand out of the water as rows of islands
        double[] lake = sampleLake(x, z);
        if (lake != null) {
            share *= 1.0 - smooth(lake[0] / LAKE_EDGE);
        }
        if (oasis != null) {
            share *= 1.0 - deserts.cleared(oasis, x, z);
        }
        return share <= 0.0 ? 0.0 : share * deserts.dunes(x, z);
    }

    /**
     * How much a position belongs to the desert by its climate and biome patches alone, from 0 at the edge of the
     * desert biome to 1 well inside it: dry enough, away from the borders of the climate zone, and in the desert's
     * patches of the zone's dry list. Each condition fades out before it fails, so terrain made for the desert never
     * steps up at its border. Needs neither the land nor the region cache, so the skeleton can ask for it.
     */
    private double desertClimate(double x, double z) {
        WorldgenSettings.Deserts config = settings.deserts;
        double dry = (settings.biomes.dryHumidity - climate.humidity(x, z)) / config.humidityFade;
        if (dry <= 0.0) {
            return 0.0;
        }
        double temperature = climate.temperature(x, z);
        double share = smooth(dry) * smooth(climate.borderDistance(temperature) / settings.climate.zoneTransition)
                * Math.max(0.0, Math.min(1.0, continents.islandClearance(x, z)));
        return share <= 0.0 ? 0.0 : share * biomes.dryShare(x, z, climate.zone(temperature), settings.biomes.desert, config.patchFade);
    }

    /**
     * How much a position belongs to desert lowland, from 0 at the edge of the desert biome to 1 well inside it: its
     * climate, off the coast, below the highland band and out of the mountains, which keep their own shape. Ground is
     * the smooth land.
     */
    double desertShare(double x, double z, double ground, double base) {
        double share = desertClimate(x, z);
        if (share <= 0.0) {
            return 0.0;
        }
        WorldgenSettings.Deserts config = settings.deserts;
        share *= 1.0 - smooth(skeleton.mountainShare(x, z, base - seaSurface()) / config.mountainFade);
        share *= smooth((sampleCoastDistance(x, z) - settings.biomes.beachDepth) / config.coastFade);
        double snowLine = sampleSnowLine(x, z);
        if (snowLine > 0.0) {
            WorldgenSettings.Biomes biomeConfig = settings.biomes;
            double highland = seaSurface() + biomeConfig.highlandLine * (snowLine - seaSurface()) - biomeConfig.bandWander;
            share *= smooth((highland - ground) / config.highlandFade);
        }
        return share;
    }

    /**
     * Ground an oasis would lie on at a position, or NaN if it cannot lie there: deep in the desert, on flat land across
     * all it clears, and clear of rivers, lakes and foreign chunks, whose water and valleys it would run into.
     */
    double oasisGround(double x, double z, double reach) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        // From the distance alone: the ground of upgraded chunks is measured against land that may hold oases
        if (desertShare(x, z, sampleSmoothLand(x, z), base) < settings.deserts.oasisMinShare || foreign.near(x, z)) {
            return Double.NaN;
        }
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river != null && river.distance() - 3.0 * river.halfWidth(settings.rivers.minChannelWidth) < reach) {
            return Double.NaN;
        }
        // Land as rivers left it, which floodplains raise or lower from the landform by several blocks
        double center = sampleRiverShapedLand(x, z);
        double low = center;
        double high = center;
        for (int k = 0; k < 4; k++) {
            double px = x + (k == 0 ? reach : k == 1 ? -reach : 0.0);
            double pz = z + (k == 2 ? reach : k == 3 ? -reach : 0.0);
            if (!Double.isNaN(sampleLakeLevel(px, pz))) {
                return Double.NaN;
            }
            double height = sampleRiverShapedLand(px, pz);
            low = Math.min(low, height);
            high = Math.max(high, height);
        }
        return high - low > settings.deserts.maxRelief || !Double.isNaN(sampleLakeLevel(x, z)) ? Double.NaN : center;
    }

    /** Whether a block column lies on the green land around an oasis. */
    public boolean isOasis(int x, int z) {
        DesertLayer.Site oasis = deserts.oasis(x, z);
        return oasis != null && deserts.isGreen(oasis, x, z);
    }

    /**
     * A creek near a position: where erosion found it, half its width there, and how far it has grown from its start.
     * Its water level takes several samples of the land around, so it is only worked out when asked for.
     */
    private final class Creek {
        final ErosionLayer.Creek hit;
        final double half;
        final double grown;
        private final double x;
        private final double z;
        private double level = Double.NaN;

        Creek(ErosionLayer.Creek hit, double half, double grown, double x, double z) {
            this.hit = hit;
            this.half = half;
            this.grown = grown;
            this.x = x;
            this.z = z;
        }

        double level() {
            if (Double.isNaN(level)) {
                level = creekLevel(x, z, this);
            }
            return level;
        }
    }

    /** The last creek query of a thread: a column asks for it for its surface and again for its water. */
    private static final class CreekMemo {
        double x = Double.NaN;
        double z;
        Creek creek;
    }

    private final ThreadLocal<CreekMemo> creekMemo = ThreadLocal.withInitial(CreekMemo::new);

    private Creek creekAt(double x, double z) {
        CreekMemo memo = creekMemo.get();
        if (memo.x != x || memo.z != z) {
            Creek creek = findCreek(x, z);
            memo.x = x;
            memo.z = z;
            memo.creek = creek;
        }
        return memo.creek;
    }

    /**
     * The creek a position lies in or beside, or null. Creeks come from the gullies erosion carved, so they only run
     * where erosion does, and give way to rivers and lakes, which they flow into. They grow from nothing over the first
     * part of their course, as rivers do from their sources, and widen with the water they gather.
     */
    private Creek findCreek(double x, double z) {
        WorldgenSettings.Erosion config = settings.erosion;
        if (!config.enabled || !config.creeks || continents.islandClearance(x, z) <= 0.0) {
            return null;
        }
        double base = interpolate(FIELD_HEIGHT, x, z);
        // Deserts have no running creeks: their hills give way to dunes, and the gullies between them stay dry. Creeks
        // are gone by the time dunes are any height, whose land the small valleys would cut off in steps
        double share = skeleton.uplandShare(x, z, base - seaSurface()) * (1.0 - skeleton.domeShare(x, z))
                * (1.0 - smooth(desertClimate(x, z) / DESERT_CREEK_FADE));
        if (share <= CREEK_SHARE) {
            return null;
        }
        ErosionLayer.Creek hit = erosion.creek(x, z, erosionWarpShare(x, z));
        if (hit == null) {
            return null;
        }
        double wide = Math.log(hit.area() / config.creekArea) / Math.log(config.creekWideArea / config.creekArea);
        double half = 0.5 * (config.creekMinWidth + (config.creekMaxWidth - config.creekMinWidth) * smooth(wide));
        double grown = smooth((hit.area() / config.creekArea - 1.0) / CREEK_GROWTH) * smooth((share - CREEK_SHARE) / CREEK_SHARE);
        if (grown <= 0.0 || hit.distance() >= half + CREEK_BANK + config.creekIncision * grown / config.creekValleyGrade) {
            return null;
        }
        // A creek runs on to the lake's water, not just to the edge of its share: the rim along a shore stands above
        // the water, and would wall the creek off from the lake
        double lake = sampleLakeLevel(x, z);
        if (!Double.isNaN(lake) && sampleCarvedLand(x, z) < lake) {
            return null;
        }
        // A creek runs on into the river it reaches, which takes over in its channel. Ending it short would leave its
        // water standing above the slope down to the river, where it would be walled in
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river != null && river.distance() < river.halfWidth(settings.rivers.minChannelWidth)) {
            return null;
        }
        return new Creek(hit, half, grown, x, z);
    }

    /**
     * Water level of a creek across from a position: the lowest ground on a line through its course, so the water
     * never stands above the bank on either side, even where the course runs a little off the floor of its gully, and
     * below that by as much as the creek has cut in. Positions across the creek share the line, and with it the level.
     * A creek never cuts below the river it runs into, or it would end lower than the river's water.
     */
    private double creekLevel(double x, double z, Creek creek) {
        double centerX = x + creek.hit.toX();
        double centerZ = z + creek.hit.toZ();
        double reach = creek.half + CREEK_BANK;
        double level = Double.MAX_VALUE;
        for (int k = -2; k <= 2; k++) {
            double across = 0.5 * k * reach;
            level = Math.min(level, sampleCarvedLand(centerX - across * creek.hit.alongZ(), centerZ + across * creek.hit.alongX()));
        }
        double cut = level - settings.erosion.creekIncision * creek.grown;
        RiverRegion.Hit river = sampleRiver(centerX, centerZ);
        return river != null && river.strength() >= SOURCE_WATER ? Math.max(cut, Math.min(level, river.level())) : cut;
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
        return skeletonHeight(x, z, base, texture, settings.erosion.enabled);
    }

    private double skeletonHeight(double x, double z, double base, double[] texture, boolean erode) {
        double clearance = continents.islandClearance(x, z);
        if (clearance <= 0.0) {
            if (texture != null) {
                texture[0] = 0.0;
            }
            return 0.0;
        }
        // With erosion, water cuts the texture into the mountains in place of the skeleton's ridged texture; cliffs stay.
        // Dome hills keep their round tops, which water would cut into a star of spurs
        boolean eroded = erode && texture != null;
        // Plains lie just above their rivers by design, and erosion there would only dig below them
        double change = eroded ? erosion.change(x, z, erosionWarpShare(x, z)) * (1.0 - skeleton.domeShare(x, z))
                * skeleton.uplandShare(x, z, base - (settings.general.seaLevel - 1)) : 0.0;
        double height = clearance * skeleton.height(x, z, base - (settings.general.seaLevel - 1), texture, !eroded, change,
                eroded ? creekCliffKeep(x, z) : 1.0);
        if (texture != null) {
            texture[0] = clearance * (texture[0] + change);
        }
        return height;
    }

    /**
     * Share of cliff bands kept at a position: they give way along creeks, which cut a notch through a band of cliffs
     * instead of running over its ledges. The ledges tilt a little against the cliff above them, wherever the levels the
     * bands step to shift faster than a gentle gully falls, and a creek on one would run uphill.
     */
    private double creekCliffKeep(double x, double z) {
        if (!settings.erosion.creeks) {
            return 1.0;
        }
        ErosionLayer.Creek creek = erosion.creek(x, z, erosionWarpShare(x, z));
        return creek == null ? 1.0 : smooth(creek.distance() / CREEK_CLIFF_FADE);
    }

    /** Surface height before water has shaped it. */
    /** Kinds of land, as the terrain skeleton lays them out. */
    public enum Landform {
        OCEAN, PLAINS, HILLS, PLATEAU, MOUNTAINS
    }

    /** The kind of land at a position, without anything rivers do to it: cheap enough to sample a whole map. */
    public Landform sampleLandformKind(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        if (softCap(x, z, base, base + skeletonHeight(x, z, base, null)) < seaSurface()) {
            return Landform.OCEAN;
        }
        return skeleton.landform(x, z, base - seaSurface());
    }

    public double sampleLandform(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double[] texture = new double[1];
        return softCap(x, z, base, base + skeletonHeight(x, z, base, texture) + texture[0]);
    }

    /**
     * Land with the skeleton's own ridge texture in place of erosion, which needs no drainage or erosion at all: for
     * maps and searches across areas far wider than the region cache, where erosion would be computed region after
     * region only to be thrown away again.
     */
    public double sampleOverviewHeight(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        double[] texture = new double[1];
        return softCap(x, z, base, base + skeletonHeight(x, z, base, texture, false) + texture[0]);
    }

    /** Surface height without ridge texture, which is the ground rivers run on. */
    public double sampleSmoothLand(double x, double z) {
        double base = interpolate(FIELD_HEIGHT, x, z);
        return softCap(x, z, base, base + skeletonHeight(x, z, base, null));
    }

    /**
     * Shapes land around water. Ridge texture fades out toward rivers, which leaves them the smooth ground they
     * were routed on, with no spurs to cut through and no gullies to bridge. Valley walls then bound the land from above and below, flat on the valley floor and
     * spreading with distance, so land only changes where it would not leave a valley. Mountains that already fall
     * away toward a river stay as they are, and a river always runs in a channel.
     */
    private double carve(double x, double z, double smooth, double textured, double dunes) {
        RiverRegion.Hit river = sampleRiver(x, z);
        double cliff = cliffHeight(x, z);
        // Without erosion, texture fades out toward rivers, which were routed on the smooth land. Erosion already cut
        // its valleys down to them, and bringing back the smooth land there would raise each river above the slopes
        // beside it, worst at its source
        double ground = cliff + (river == null || settings.erosion.enabled ? textured : smooth + (textured - smooth) * river.texture());
        double land = river == null ? ground : barred(x, z, river, duned(x, z, river, carveValley(x, z, river, ground)));
        land = fanned(x, z, river, land);
        double[] oxbow = oxbowAt(x, z, river);
        if (oxbow != null) {
            // A lowered pond must not cut a vertical step around its entire footprint. The excavation fades into
            // the existing bank; water only occupies the part of this hollow that really lies below its flat level.
            land = Math.min(land, land + (oxbow[0] - settings.rivers.oxbowDepth - land) * oxbow[1]);
        }
        double[] lake = sampleLake(x, z);
        if (lake == null) {
            return duned(x, z, river, land, dunes);
        }
        // Lakes fill the land as it is, so shores follow the terrain. Inside one, valley banks give way to the basin,
        // and along its edge a rim closes any gap below the water. Land is never pulled down to a lake: rivers nearby
        // can run higher, and the ground beside them would drop away.
        // What the river cut stays, though: where the land stands above the water, the river still needs its channel
        // through, and a rim across the channel would dam it
        double inside = smooth((lake[0] - LAKE_EDGE) / LAKE_EDGE);
        double shaped = land > ground ? land + (ground - land) * inside : land;
        boolean channel = river != null && river.distance() < river.halfWidth(settings.rivers.minChannelWidth);
        double rim = channel ? 0.0 : smooth(lake[0] / LAKE_EDGE) * (1.0 - inside);
        double bank = lake[1] + settings.rivers.bankHeight;
        return duned(x, z, river, sinkBasin(x, z, lake, shaped < bank ? shaped + (bank - shaped) * rim : shaped), dunes);
    }

    /**
     * Land with alluvial fans on it. They go on after the valleys are shaped, which would otherwise flatten them into
     * the floodplain or cut them back to the valley walls, and stay off the channel: the stream runs in a trench through
     * its own fan, and the sea floor stays as it is.
     */
    private double fanned(double x, double z, RiverRegion.Hit river, double land) {
        WorldgenSettings.Rivers config = settings.rivers;
        if (!config.fans || land < seaSurface()) {
            return land;
        }
        double deposit = riversAt(x, z).fanDeposit(x, z, config.fanSpread);
        if (deposit <= 0.0) {
            return land;
        }
        double trench = river == null ? 1.0 : smooth((river.distance() - river.halfWidth(config.minChannelWidth)) / config.fanTrench);
        return land + deposit * trench;
    }

    /**
     * Land with desert dunes on it. They go on last, after the valleys and lake basins are shaped: desert lowland lies
     * on wide floodplains, which would lay dunes flat with the rest of their low relief, and the rims of inland sinks
     * cap the land around them. Dunes fade out toward lakes on their own. They stay off valley floors, out of
     * the water, and off the sea floor. The distance is to the nearest valley floor of all rivers in reach: the river
     * a position counts as being in changes from one column to the next, and its distance would jump with it. Oxbow
     * lakes hold the land down to their water at their banks, so dunes give way to them before that.
     */
    private double duned(double x, double z, RiverRegion.Hit river, double land, double dunes) {
        if (dunes <= 0.0 || land < seaSurface()) {
            return land;
        }
        if (river == null) {
            return land + dunes;
        }
        WorldgenSettings.Deserts config = settings.deserts;
        double share = smooth((river.run() - config.riverGap) / config.riverFade);
        if (share > 0.0 && settings.rivers.oxbows) {
            share *= smooth(riversAt(x, z).oxbowClearance(x, z, config.riverFade) / config.riverFade);
        }
        return land + dunes * share;
    }

    /**
     * The oxbow lake at a position, as {@link RiverRegion#oxbow} gives it, or null. Oxbows keep clear of the channel of
     * any river where it bends closer than they were laid out for: a crescent running into the river would drain it.
     */
    private double[] oxbowAt(double x, double z, RiverRegion.Hit river) {
        WorldgenSettings.Rivers config = settings.rivers;
        if (!config.oxbows || river != null && river.distance() < river.halfWidth(config.minChannelWidth) + 0.5 * config.oxbowGap) {
            return null;
        }
        return riversAt(x, z).oxbow(x, z);
    }

    /** Ground containing an oxbow, without its excavation. This never queries oxbows, so fitted levels stay acyclic. */
    private double sampleOxbowBank(double x, double z) {
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river == null || river.distance() < river.halfWidth(settings.rivers.minChannelWidth) + 0.5 * settings.rivers.oxbowGap) {
            return Double.NaN;
        }
        double ground = sampleLandform(x, z) + cliffHeight(x, z);
        return barred(x, z, river, duned(x, z, river, carveValley(x, z, river, ground)));
    }

    /**
     * Land dug out for an inland lake, which unlike other lakes has no hollow of its own to fill. Around it, land is
     * held down to the rim, more firmly the more the sink holds a position. Held down at full strength wherever the sink
     * reached at all, which ends at the centers of the routing cells beyond it, higher land around it such as a sea
     * cliff was cut flat along straight lines meeting at right angles, with walls thirty blocks high.
     */
    private double sinkBasin(double x, double z, double[] lake, double land) {
        if (lake[2] <= 0.0) {
            return land;
        }
        WorldgenSettings.Rivers rivers = settings.rivers;
        double dug = smooth((lake[2] + rivers.sinkWarp * sinkShore.sample(x, z) - 0.2) / 0.5);
        double rim = lake[1] + rivers.sinkRim;
        double held = Math.min(land, rim + (lake[1] - rivers.sinkDepth - rim) * dug);
        return land + (held - land) * smooth(lake[2] / SINK_RIM_FADE);
    }

    /** Whether a sea stack can stand at a position: in the water a little way off a rocky coast. */
    boolean isStackSite(double x, double z) {
        double coast = sampleCoastDistance(x, z);
        return -coast >= settings.seaStacks.distanceMin && -coast <= settings.seaStacks.distanceMax && isRockyCoast(x, z);
    }

    double seaSurface() {
        return settings.general.seaLevel - 1;
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

    /** Whether a position lies on the sand along a river mouth. The edge wanders, or it would trace the river exactly. */
    private boolean isMouthSand(double x, double z, RiverRegion.Hit river) {
        WorldgenSettings.Rivers rivers = settings.rivers;
        return river.sandRun() < rivers.mouthSandWidth * (1.0 + rivers.mouthSandContrast * mouthSandNoise.sample(x, z));
    }

    /** Whether a block column on the banks of a river mouth is a clay flat rather than sand. */
    public boolean isMouthClay(int x, int z) {
        if (mouthClayNoise.sample(x, z) <= settings.rivers.mouthClayThreshold) {
            return false;
        }
        RiverRegion.Hit river = sampleRiver(x, z);
        return river != null && isMouthSand(x, z, river);
    }

    /**
     * How strongly a sediment bar claims a position, from 0 to 1: the bar the river lays there, in the stretches the
     * noise leaves it. Bars come and go along a river instead of lining it as an even band.
     */
    private double barShare(double x, double z, RiverRegion.Hit river) {
        WorldgenSettings.Rivers rivers = settings.rivers;
        if (!rivers.bars || river.bar() <= 0.0) {
            return 0.0;
        }
        return river.bar() * smooth(0.5 + 1.5 * (barNoise.sample(x, z) + rivers.barCoverage));
    }

    /**
     * Land laid flat by a sediment bar: a beach just above the water, between the channel and the rising bank. It only
     * lowers land, by at most barRelief, and lets land higher than that above the beach be, so valley sides and high
     * banks stay as they are.
     */
    private double barred(double x, double z, RiverRegion.Hit river, double land) {
        double share = barShare(x, z, river);
        double beach = river.level() + settings.rivers.barHeight;
        double above = land - beach;
        double relief = settings.rivers.barRelief;
        if (share <= 0.0 || above <= 0.0 || above >= 2.0 * relief) {
            return land;
        }
        return land - Math.min(above, relief) * share * (1.0 - smooth((above - relief) / relief));
    }

    /**
     * The sediment bar on a block column: 0 for none, 1 for sand, 2 for gravel. Only where the bar is the ground, on its
     * flat just above the water and under the shallows, not up the bank behind it.
     */
    public int riverBar(int x, int z) {
        return columns.get().column(this, x, z).bar();
    }

    private int sampleRiverBar(double x, double z) {
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river == null || barShare(x, z, river) < 0.5
                || sampleSurfaceHeight(x, z) > river.level() + settings.rivers.barHeight + 1.0) {
            return 0;
        }
        // Gravel gives way to sand in patches, not along a line across the river
        return river.coarse() > 0.5 + GRAIN_PATCHES * grainNoise.sample(x, z) ? 2 : 1;
    }

    /** Land with low dunes where it lies on the sand of a river mouth, rising from the water's edge. */
    private double duned(double x, double z, RiverRegion.Hit river, double land) {
        double reach = 2.0 * settings.rivers.mouthSandWidth;
        if (river.sandRun() >= reach || land < seaSurface()) {
            return land;
        }
        double fade = (1.0 - smooth(river.sandRun() / reach)) * smooth(river.sandRun() / 12.0);
        return land + settings.rivers.duneHeight * fade * duneNoise.sample(x, z);
    }

    private double carveValley(double x, double z, RiverRegion.Hit river, double ground) {
        return grow(river, ground, carveChannel(x, z, river, ground));
    }

    /**
     * Land a river shapes near its source, where it has not grown yet: its water level is that of the smooth ground it
     * was routed on, and spurs higher than that would get a narrow slot cut through them, so it only cuts as much as
     * it has grown. It holds no water before it is half grown, and raises no banks either, which would stand as a ridge
     * along the gully it starts in.
     */
    private static double grow(RiverRegion.Hit river, double ground, double carved) {
        if (carved < ground) {
            return ground + (carved - ground) * river.strength();
        }
        return river.strength() >= SOURCE_WATER ? carved : ground;
    }

    private double carveChannel(double x, double z, RiverRegion.Hit river, double ground) {
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
            // A higher river beside a lower one, or the pool above a waterfall beside the one below: the banks that
            // hold the higher water win, and the land stands as a step above the lower water
            walled = river.low();
        } else {
            walled = ground > river.high() ? valleyWall(x, z, river, ground) : Math.max(ground, river.low());
            walled = terraced(x, z, river, walled);
        }
        // Walls of a much higher or lower river nearby may disagree, but a river's banks always meet its own water
        double bank = river.level() + settings.rivers.bankHeight;
        walled = flatten(river, walled, bank);
        // The bank comes from the blended water level, which a lower river or the pool below a waterfall pulls down;
        // land never goes below the banks of the highest water around
        double land = Math.max(river.low(), bank + (walled - bank) * smooth(river.run() / settings.rivers.valleyBankBlend));
        return seaFloor + (land - seaFloor) * smooth((ground - sea) / settings.rivers.coastBlend);
    }

    /**
     * Land cut back to a valley's upper wall. A plain limit would shave every slope into the same smooth bowl with a
     * crease along its edge, so the wall wanders away from the river and land overtops it a little before it levels off.
     */
    private double valleyWall(double x, double z, RiverRegion.Hit river, double ground) {
        WorldgenSettings.Rivers rivers = settings.rivers;
        double wall = river.high() + rivers.valleyWallRelief * smooth(river.run() / rivers.valleyBankBlend) * valleyWallNoise.sample(x, z);
        if (ground <= wall) {
            return Math.max(ground, river.low());
        }
        double soft = rivers.valleyWallSoftness;
        return Math.max(wall + soft * (1.0 - Math.exp((wall - ground) / soft)), river.low());
    }

    /**
     * Land on the sides of a valley stepped into river terraces: flat benches at fixed heights above the water, each
     * the floor of an older valley the river has since cut below, with steep risers between them. A bench runs along
     * the river at one height above it, so it follows the valley. Terraces come in stretches, on one bank or both, in
     * hills and mountains beside the larger rivers, and only ever take land away.
     */
    private double terraced(double x, double z, RiverRegion.Hit river, double land) {
        WorldgenSettings.Rivers config = settings.rivers;
        if (!config.terraces || river.width() < config.terraceMinWidth || river.strength() < 1.0) {
            return land;
        }
        double step = config.terraceStep;
        // The heights of the benches wander a little along the valley, so neighboring valleys do not share them
        double rise = land - river.level() - config.bankHeight + config.terraceHeightWander * step * terraceHeightNoise.sample(x, z);
        double top = step * config.terraceCount;
        if (rise <= 0.0 || rise >= top + step || river.run() >= config.terraceReach) {
            return land;
        }
        double share = smooth((terraceNoise.sample(x, z) - (1.0 - 2.0 * config.terraceCoverage)) / TERRACE_EDGE)
                * (1.0 - smooth(river.run() / config.terraceReach)) * (1.0 - smooth((rise - top) / step));
        if (share <= 0.0) {
            return land;
        }
        double base = interpolate(FIELD_HEIGHT, x, z);
        // Valleys reach into the plains, where their sides are low and terraces would only trace contour lines
        share *= smooth(skeleton.uplandShare(x, z, base - seaSurface()) / TERRACE_UPLAND);
        double steps = rise / step;
        double floor = Math.floor(steps);
        double stepped = (floor + Math.pow(steps - floor, config.terraceSharpness)) * step;
        return land - (rise - stepped) * share;
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
        // Like the water itself, the lake only takes over where its water stands above the ground
        double[] lake = sampleLake(x, z);
        return lake == null || lake[0] <= LAKE_EDGE || sampleSurfaceHeight(x, z) >= lake[1];
    }

    /** Whether a position lies in a river channel as the seed routes it, which needs no erosion to answer. */
    private boolean isNaturalRiverChannel(double x, double z) {
        RiverRegion.Hit river = naturalRiver(x, z);
        return river != null && river.distance() < river.halfWidth(settings.rivers.minChannelWidth);
    }

    /** Water surface of a column, or NaN where it holds no river or lake. */
    public double sampleWaterLevel(double x, double z) {
        ForeignMap map = foreign;
        if (!map.isEmpty()) {
            double smooth = sampleSmoothLand(x, z);
            ForeignMap.Influence influence = map.influence(x, z, smooth);
            if (influence != null) {
                return foreignWaterLevel(x, z, smooth, influence);
            }
        }
        return plainWaterLevel(x, z);
    }

    /**
     * Water where a blend reaches. Foreign water spreads past the edge. Any other water never rises with the land, which
     * would tilt whole rivers and lakes up the slope: land that comes up past it simply ends it at a shore. Land that
     * sinks takes the water down with it, or the water would stand above its banks.
     */
    private double foreignWaterLevel(double x, double z, double smooth, ForeignMap.Influence influence) {
        double pond = influence.wetLevel();
        // Around old chunks, water of their own that this side also has runs on as this side's
        if (!Double.isNaN(pond) && influence.wetDistance() < settings.foreign.pondReach && sampleSurfaceHeight(x, z) < pond
                && !(influence.upgraded() && hasOwnWater(x, z, settings.foreign.pondReach))) {
            return pond;
        }
        double level = plainWaterLevel(x, z);
        if (Double.isNaN(level)) {
            return Double.NaN;
        }
        double[] held = heldWater(x, z, influence);
        if (held != null) {
            return influence.distance() < damFoot(held[0], influence) || sampleSurfaceHeight(x, z) >= level ? Double.NaN : level;
        }
        if (influence.upgraded()) {
            // Rivers move with the land they run in; lakes stay level, and their shore follows the shifted land
            double[] lake = sampleLake(x, z);
            double shifted = lake != null && lake[0] > LAKE_EDGE ? level : level + influence.offset() * influence.share();
            return sampleSurfaceHeight(x, z) >= shifted ? Double.NaN : shifted;
        }
        if (sampleSurfaceHeight(x, z) >= level) {
            return Double.NaN;
        }
        return Math.min(level, closeIn(smooth, level, influence.ground(), foreignShare(x, z, influence)));
    }

    private double plainWaterLevel(double x, double z) {
        double[] lake = sampleLake(x, z);
        if (lake != null && lake[0] > LAKE_EDGE && sampleSurfaceHeight(x, z) < lake[1]) {
            return lake[1];
        }
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river != null && river.strength() >= SOURCE_WATER && river.distance() < river.halfWidth(settings.rivers.minChannelWidth)) {
            return river.level();
        }
        double[] oxbow = oxbowAt(x, z, river);
        if (oxbow != null && sampleSurfaceHeight(x, z) < oxbow[0]) {
            return oxbow[0];
        }
        DesertLayer.Site oasis = deserts.oasis(x, z);
        if (oasis != null && deserts.inPond(oasis, x, z) && sampleSurfaceHeight(x, z) < oasis.level()) {
            return oasis.level();
        }
        // Water two blocks wide does not hold, so a creek only fills once it has grown half way
        Creek creek = creekAt(x, z);
        return creek != null && creek.grown >= 0.5 && creek.hit.distance() < creek.half ? creek.level() : Double.NaN;
    }

    /**
     * Lake influence at a position, as {share of lake, blended smoothly, the lake's water level, and the share of it
     * that is an inland sink}, or null away from lakes. Lakes take their shape from the ground ({@link LakeLayer});
     * inland sinks, which dig their own basin, from their routing cells.
     */
    private double[] sampleLake(double x, double z) {
        double[] lake = naturalLake(x, z);
        double[] sink = sinkLake(x, z);
        return sink == null || lake != null && lake[0] >= sink[0] ? lake : sink;
    }

    private double[] naturalLake(double x, double z) {
        int size = settings.rivers.flowCellSize;
        int cells = settings.general.regionSize / size;
        int cellX = (int) Math.floor(x / size);
        int cellZ = (int) Math.floor(z / size);
        LakeLayer.LakeRegion region = lakeRegionAt(Math.floorDiv(cellX, cells), Math.floorDiv(cellZ, cells));
        double best = 0.0;
        double level = Double.NaN;
        for (LakeLayer.Shape shape : region.at(Math.floorMod(cellX, cells), Math.floorMod(cellZ, cells))) {
            double share = shape.share(x, z);
            if (share > best) {
                best = share;
                level = shape.level;
            }
        }
        return best > 0.0 ? new double[]{best, level, 0.0} : null;
    }

    private LakeLayer.LakeRegion lakeRegionAt(int regionX, int regionZ) {
        LakeLayer.LakeRegion region = lastLakes.get();
        if (region == null || region.regionX() != regionX || region.regionZ() != regionZ) {
            region = lakeRegions.get(regionX, regionZ);
            lastLakes.set(region);
        }
        return region;
    }

    /** Inland sink influence at a position, from the routing cells around it, in the form of {@link #sampleLake}. */
    private double[] sinkLake(double x, double z) {
        int size = settings.rivers.flowCellSize;
        double u = x / size - 0.5;
        double v = z / size - 0.5;
        int cellX = (int) Math.floor(u);
        int cellZ = (int) Math.floor(v);
        double tx = smooth(u - cellX);
        double tz = smooth(v - cellZ);
        double share = 0.0;
        double levelSum = 0.0;
        double sink = 0.0;
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                int cells = settings.general.regionSize / size;
                FlowRegion flow = flowRegionAt(Math.floorDiv(cellX + dx, cells), Math.floorDiv(cellZ + dz, cells));
                int lx = Math.floorMod(cellX + dx, cells);
                int lz = Math.floorMod(cellZ + dz, cells);
                if (flow.isSink(lx, lz)) {
                    double weight = (dx == 0 ? 1.0 - tx : tx) * (dz == 0 ? 1.0 - tz : tz);
                    share += weight;
                    levelSum += weight * flow.lakeLevel(lx, lz);
                    sink += weight;
                }
            }
        }
        return share > 0.0 ? new double[]{share, levelSum / share, sink} : null;
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
        return softCap(x, z, base, base + skeletonHeight(x, z, base, null));
    }

    /**
     * Leaves heights below the cap band untouched and approaches the cap asymptotically above it. The cap is the design
     * height, rising toward the surface maximum with the plateau share, where the mountains standing on plateaus are
     * the only land that rises above the design height.
     */
    private double softCap(double x, double z, double base, double height) {
        WorldgenSettings.General general = settings.general;
        double fraction = general.softCapFraction;
        // Below the band of the design height, no cap reaches
        if (height <= general.designHeight * (1.0 - fraction)) {
            return height;
        }
        double cap = general.designHeight;
        if (general.maxSurfaceHeight > general.designHeight) {
            cap += (general.maxSurfaceHeight - general.designHeight) * skeleton.plateauShareAt(x, z, base - seaSurface());
        }
        double band = fraction * cap;
        double start = cap - band;
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
        if (y < column.height - settings.biomes.caveDepth && biomes.isDeepDark(x, y, z)) {
            return settings.biomes.deepDarkBiome;
        }
        if (y < column.height - settings.biomes.caveDepth && biomes.isCave(x, z)) {
            return biomes.caveBiome(climate.humidity(x, z));
        }
        return column.biome();
    }

    /**
     * Surface biome a position about has, from smooth land without erosion, rivers or lakes. For searches far and
     * wide, such as where vanilla places strongholds when a world is created: thousands of samples over tens of
     * thousands of blocks would otherwise erode every region they reach, which held new worlds at 0% for minutes.
     */
    public String estimateBiome(double x, double z) {
        double temperature = climate.temperature(x, z);
        return biomes.surfaceBiome(x, z, climate.zone(temperature), sampleSmoothLand(x, z), sampleCoastDistance(x, z),
                sampleSnowLine(x, z), climate.humidity(x, z), false, isRockyCoast(x, z), continents.islandKind(x, z),
                climate.nearZoneBorder(temperature), !settings.biomes.unrealisticOnMountains && isMountainous(x, z));
    }

    /** Surface biome id of a position. */
    public String sampleBiome(double x, double z) {
        double temperature = climate.temperature(x, z);
        ForeignMap map = foreign;
        ForeignMap.Influence influence = map.isEmpty() ? null : map.influence(x, z, sampleSmoothLand(x, z));
        double height = sampleSurfaceHeight(x, z);
        String foreignBiome = influence == null ? null : biomes.foreignBiome(x, z, influence, climate.zone(temperature), height);
        if (foreignBiome != null) {
            return foreignBiome;
        }
        String biome = biomes.surfaceBiome(x, z, climate.zone(temperature), height, sampleCoastDistance(x, z),
                sampleSnowLine(x, z), climate.humidity(x, z), isRiverChannel(x, z), isRockyCoast(x, z), continents.islandKind(x, z),
                climate.nearZoneBorder(temperature), !settings.biomes.unrealisticOnMountains && isMountainous(x, z));
        // The green land of an oasis brings grass and a few trees along with its biome
        DesertLayer.Site oasis = biome.equals(settings.biomes.desert) ? deserts.oasis(x, z) : null;
        return oasis != null && deserts.isGreen(oasis, x, z) ? settings.biomes.oasis : biome;
    }

    /**
     * Whether a position lies on a mountain range, its slopes included, rather than in hills or plains. Plateaus do
     * not count even inside the mountain tier: badlands mesas are what plateaus look like in a dry climate.
     */
    private boolean isMountainous(double x, double z) {
        double baseAboveSea = interpolate(FIELD_HEIGHT, x, z) - seaSurface();
        return skeleton.mountainShare(x, z, baseAboveSea) > settings.biomes.mountainShare
                && skeleton.landform(x, z, baseAboveSea) != Landform.PLATEAU;
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

    /**
     * Rock of a stratum at a height on steep faces: 0 for plain stone and 1 to 4 for the other rocks, dark ones on igneous ranges and
     * pale ones on wind-worn ranges. Layers wander a little and repeat at no fixed order, like real bedding.
     */
    public int rockStratum(int x, int y, int z) {
        Column column = columns.get().column(this, x, z);
        if (column.grade() * 100.0 < settings.surface.strataSlope) {
            return 0;
        }
        int band = (int) Math.floor((y + column.strataOffset()) / settings.surface.strataThickness);
        double roll = Hashing.unit(Hashing.hash(strataSeed, band, 0));
        if (roll < 0.6) {
            return 0;
        }
        return (column.igneous() ? 1 : 3) + (roll < 0.85 ? 0 : 1);
    }

    /** Share of cliff bands at a position, from 0 to 1. Slope limits do not hold where it is above zero. */
    public double sampleCliffShare(double x, double z) {
        if (stacks.raise(x, z, Double.NEGATIVE_INFINITY) != Double.NEGATIVE_INFINITY || sinkholes.contains(x, z)) {
            return 1.0;
        }
        double base = interpolate(FIELD_HEIGHT, x, z);
        return skeleton.cliffShare(x, z, base - (settings.general.seaLevel - 1));
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
        if (stacks.contains(x, z)) {
            return true;
        }
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
        RiverRegion.Hit river = sampleRiver(x, z);
        if (river != null && isMouthSand(x, z, river) && sampleSurfaceHeight(x, z) < settings.general.seaLevel - 1 + settings.beaches.maxHeight) {
            return true;
        }
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
                    // Most far samples are open sea, and this rules them out without touching the region cache. The rest
                    // only reads smooth land and water, never erosion: that costs about a second for each new region the
                    // search reaches, and up to four of them for a sample near a region border, which held a new world at
                    // 0% for most of a minute. Spawn only needs a column; the game finds its height in the chunk
                    double minimum = sea + settings.spawn.minAboveSea;
                    if (sampleContinentHeight(x, z) >= sea && isSpawnCell(cellX, cellZ, sea) && sampleSmoothLand(x, z) >= minimum
                            && Double.isNaN(sampleLakeLevel(x, z)) && !isNaturalRiverChannel(x, z)
                            && (foreign.isEmpty() || Double.isNaN(sampleWaterLevel(x, z)))) {
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
        return findNearest(originX, originZ, step, radius, (x, z) -> sampleOverviewHeight(x, z) >= minHeight);
    }

    /** Kinds of land {@link #findNearest(Feature, int, int, int, int)} looks for. */
    public enum Feature {
        /** Land above half the maximum surface height. */
        MOUNTAIN,
        PLAINS,
        PLATEAU,
        LAKE,
        OCEAN
    }

    /**
     * Nearest column of a kind of land, searching square rings outward from an origin. Mountains, plains, plateaus
     * and oceans come from the skeleton alone, which needs no drainage or erosion, so the search can range across many
     * regions. Lakes need the drainage and lake regions it passes, but still no erosion.
     *
     * @param step   ring spacing and sample spacing in blocks
     * @param radius search radius in blocks
     * @return the first matching column found, or null if none within the radius
     */
    public BlockColumn findNearest(Feature feature, int originX, int originZ, int step, int radius) {
        return switch (feature) {
            case MOUNTAIN -> findNearestAbove(settings.general.designHeight / 2.0, originX, originZ, step, radius);
            case PLAINS -> findNearest(originX, originZ, step, radius, (x, z) -> sampleLandformKind(x, z) == Landform.PLAINS);
            case PLATEAU -> findNearest(originX, originZ, step, radius, (x, z) -> sampleLandformKind(x, z) == Landform.PLATEAU);
            // Real ocean, not the coastal shallows a step off the beach
            case OCEAN -> findNearest(originX, originZ, step, radius, (x, z) -> sampleLandformKind(x, z) == Landform.OCEAN
                    && sampleCoastDistance(x, z) < -OCEAN_OFFSHORE);
            case LAKE -> findNearest(originX, originZ, step, radius, (x, z) -> !Double.isNaN(sampleLakeLevel(x, z))
                    && sampleSmoothLand(x, z) < sampleLakeLevel(x, z));
        };
    }

    private BlockColumn findNearest(int originX, int originZ, int step, int radius, BiPredicate<Integer, Integer> matches) {
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
                    if (distance < bestDistance && matches.test(x, z)) {
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

    /** Smooth land at a cell center, without erosion, which the spawn search must not wait for. */
    private double cellCenterSurface(int cellX, int cellZ) {
        double size = settings.general.cellSize;
        return sampleSmoothLand((cellX + 0.5) * size, (cellZ + 0.5) * size);
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
            if (column == null || column.owner != logic || column.x != x || column.z != z || column.foreign != logic.foreign) {
                column = new Column(logic, x, z);
                slots[index] = column;
            }
            return column;
        }
    }

    /** Per-column values; everything but the height is computed only when first asked for. */
    private static final class Column {
        final TerrainLogic owner;
        final ForeignMap foreign;
        final int x;
        final int z;
        final double height;
        final double shaped;
        private double coastDistance = Double.NaN;
        private int beach = -1;
        private int bar = -1;
        private double grade = Double.NaN;
        private int snow = -1;
        private int rock = -1;
        private String biome;
        private CoastLayer.Type coastType;
        private double strataOffset = Double.NaN;
        private int igneous = -1;
        private double[] overhangProfile;
        private SinkholeLayer.Site sinkhole;
        private boolean sinkholeKnown;
        private double highestSolid = Double.NaN;

        Column(TerrainLogic owner, int x, int z) {
            this.owner = owner;
            this.foreign = owner.foreign;
            this.x = x;
            this.z = z;
            this.shaped = owner.sampleShapedLand(x, z);
            this.height = owner.blendForeign(x, z, owner.sinkholes.lower(x, z, owner.stacks.raise(x, z, shaped)));
        }

        double[] overhangProfile() {
            if (overhangProfile == null) {
                overhangProfile = owner.sampleOverhangProfile(x, z, height);
            }
            return overhangProfile;
        }

        double highestSolid() {
            if (Double.isNaN(highestSolid)) {
                highestSolid = height;
                for (double sample : overhangProfile()) {
                    highestSolid = Math.max(highestSolid, sample);
                }
            }
            return highestSolid;
        }

        double solidHeight(int y) {
            if (!sinkholeKnown) {
                sinkhole = owner.sinkholes.near(x, z);
                sinkholeKnown = true;
            }
            if (sinkhole != null && !owner.foreignNear(x, z)) {
                double inside = owner.sinkholes.chamberDepth(sinkhole, x, y, z);
                if (inside > 0.0) {
                    return Math.min(height, y - inside);
                }
            }
            double[] profile = overhangProfile();
            if (profile == NO_OVERHANG || y > highestSolid()) {
                return height;
            }
            double position = (owner.overhangs.shift(x, y, z) + 1.0) * 0.5 * (profile.length - 1);
            int index = Math.min((int) position, profile.length - 2);
            return profile[index] + (profile[index + 1] - profile[index]) * (position - index);
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

        double strataOffset() {
            if (Double.isNaN(strataOffset)) {
                strataOffset = owner.settings.surface.strataWarp * owner.strataNoise.sample(x, z);
            }
            return strataOffset;
        }

        boolean igneous() {
            if (igneous < 0) {
                igneous = owner.skeleton.isIgneous(x, z) ? 1 : 0;
            }
            return igneous == 1;
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

        int bar() {
            if (bar < 0) {
                bar = owner.sampleRiverBar(x, z);
            }
            return bar;
        }
    }
}
