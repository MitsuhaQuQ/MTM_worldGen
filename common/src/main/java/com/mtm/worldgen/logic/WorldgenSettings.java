package com.mtm.worldgen.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every terrain parameter of a world, read from config/mtm_worldgen.json when a world is created and then locked
 * into that world's level.dat. Field defaults are the design values; fields missing from a JSON file keep them.
 * Treat instances as immutable: {@link TerrainLogic} instances are shared by their JSON form.
 *
 * <p>Heights in {@link Mountains}, {@link Plateaus} and {@link Hills} are designed for a 300 block maximum surface
 * and scale with {@link General#maxSurfaceHeight}.
 */
public final class WorldgenSettings {
    public static final WorldgenSettings DEFAULT = new WorldgenSettings();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Bottom of MTM worlds, unchanged from vanilla. */
    private static final int MIN_Y = -64;
    /** Vanilla limits the top of the world to this. */
    private static final int MAX_BUILD_TOP = 2032;
    /** Empty space kept above the highest possible surface. */
    private static final int BUILD_HEADROOM = 40;

    public final General general = new General();
    public final Slopes slopes = new Slopes();
    public final Continents continents = new Continents();
    public final Mountains mountains = new Mountains();
    public final Plateaus plateaus = new Plateaus();
    public final Hills hills = new Hills();
    public final Beaches beaches = new Beaches();
    public final Surface surface = new Surface();
    public final Spawn spawn = new Spawn();
    public final Climate climate = new Climate();
    public final Rivers rivers = new Rivers();
    public final Biomes biomes = new Biomes();

    public static final class General {
        /** Villages only start where the ground within villageRadius blocks varies by at most villageMaxRelief. */
        public int villageRadius = 48;
        public double villageMaxRelief = 10.0;
        /** Vanilla sea level field; the top water block sits at seaLevel - 1 (y=62). */
        public int seaLevel = 63;
        /** Highest surface height terrain may reach. The build height of new worlds follows from it. */
        public int maxSurfaceHeight = 750;
        /** Logic region edge length in blocks. */
        public int regionSize = 4096;
        /** Coarse grid cell edge length in blocks; regionSize must be a multiple of it. */
        public int cellSize = 64;
        /** Radius in blocks read around a region when computing it. */
        public int lookAhead = 4096;
        /** Logic regions kept in memory. */
        public int regionCacheSize = 64;
        /** The top fraction of the height range is compressed so peaks approach but never exceed the maximum. */
        public double softCapFraction = 0.13;
        /** Skeleton fades in while the continent base rises this many blocks above the sea. */
        public double coastFadeHeight = 8.0;
        /** Blocks kept above the surface when filling chunks, for terrain raised under structures. */
        public int structureTerrainMargin = 32;
        /** Caves and ravines stay at least this many blocks below the surface. */
        public int caveSurfaceMargin = 12;
    }

    /** Maximum grades in percent (height difference / horizontal distance * 100). */
    public static final class Slopes {
        public double plains = 5.0;
        public double hills = 25.0;
        public double plateau = 40.0;
        public double mountain = 150.0;
    }

    public static final class Continents {
        /** Target fraction of the surface covered by ocean. */
        public double oceanRatio = 0.30;
        /** Average distance between plate centers in blocks. */
        public double plateSpacing = 11000.0;
        /** How far plate centers move from their grid cell center, as a fraction of the spacing. */
        public double plateJitter = 0.85;
        /** Fraction of plates that are open ocean instead of continent. */
        public double oceanPlateFraction = 0.18;
        /** Spacing of continent group sites in plate units: roughly how many plates wide a continent is. */
        public double groupSpacing = 2.6;
        public double warpWavelength = 4096.0;
        public double warpAmplitude = 2600.0;
        /** Strait half-width offset between continent groups in plate units; negative closes it into land. */
        public double straitMean = 0.02;
        public double straitVariation = 0.16;
        public double straitWavelength = 6144.0;
        public double detailWavelength = 2048.0;
        public double detailAmplitude = 0.06;
        /** Land rises this far above the sea in continent interiors, before the skeleton. */
        public double landRange = 72.0;
        public double oceanDepth = 36.0;
    }

    public static final class Mountains {
        public double zoneWavelength = 7168.0;
        /** Fraction of the world inside orogeny zones. */
        public double zoneCoverage = 0.32;
        /** Mountains keep this many blocks from the coast, varying along it down to nothing, so some still meet the sea. */
        public double coastMargin = 2400.0;
        public double coastMarginWavelength = 6144.0;
        public double zoneFade = 0.05;
        public double siteSpacing = 1536.0;
        public double siteJitter = 0.8;
        /** Sites need at least this much zone weight to carry a mountain. */
        public double siteMinZone = 0.1;
        /** Broad uplift of the whole orogeny zone. */
        public double massifHeight = 70.0;
        /** Ridge and peak height above the massif. */
        public double peakMin = 60.0;
        public double peakMax = 170.0;
        public double ridgeWarp = 700.0;
        public double ridgeWarpWavelength = 3072.0;
        /** Minimum cosine between a link and the orientation field for two range sites to join. */
        public double linkMinAlignment = 0.5;
        public double orientationWavelength = 16384.0;
        public double ridgeWavelength = 1024.0;
        /** Ridge height varies along the range by this fraction. */
        public double ridgeVariation = 0.2;
        /** Spur and gully texture amplitude, as a fraction of the local mountain height. */
        public double textureShare = 0.25;
        public double textureWavelength = 900.0;
        /** The texture is this many times longer across a ridge than along it, so its creases run down the flanks. */
        public double textureStretch = 3.0;
        /** How far spurs wander sideways on their way down, in blocks. */
        public double textureBend = 700.0;
        /** Spurs follow a ridge out to this distance, well past its foot, so open slopes run downhill too. */
        public double textureReach = 2500.0;
        /** Wavelength of the texture on open massif land away from ridges. */
        public double massifTextureWavelength = 1400.0;
        /** Strength of the fine gullies relative to the texture; they break smooth slopes into uneven block steps. */
        public double gullyShare = 0.5;
        public double gullyWavelength = 240.0;
        /** Share of the massif uplift the texture also applies to, so land between ridges is not a flat table. */
        public double massifTextureShare = 0.5;
        /** Mix between a rounded (0) and a sharp, concave (1) mountain cross section. */
        public double crestSharpness = 0.15;
        /** Width in blocks of the gentle band along the crest, centered on the ridge line. */
        public double summitWidth = 120.0;
        /** How much the texture is reduced on the summit band, from 0 (not at all) to 1 (fully). */
        public double summitTextureReduction = 0.75;
        /** Range width in blocks at half the mountain height, chosen per site. Taller mountains may exceed it to respect the slope limit. */
        public double widthMin = 500.0;
        public double widthMax = 1000.0;
        /** Share of the slope limit used by the main profile; the rest is headroom for texture and foothills. */
        public double profileSlopeShare = 0.38;
        /** Blend width in blocks where two mountain profiles meet. */
        public double mergeWidth = 24.0;
        public double foothillHeight = 32.0;
        public double foothillWavelength = 512.0;
    }

    public static final class Plateaus {
        public double zoneWavelength = 9216.0;
        public double coverage = 0.07;
        public double fade = 0.2;
        public double minHeight = 40.0;
        public double maxHeight = 80.0;
    }

    public static final class Hills {
        public double zoneWavelength = 5120.0;
        public double coverage = 0.35;
        public double fade = 0.15;
        public double height = 26.0;
        public double wavelength = 640.0;
    }

    public static final class Beaches {
        /** Beach depth inland varies along the coast between these; negative leaves coast without a beach. */
        public double minWidth = -8.0;
        public double maxWidth = 96.0;
        public double widthWavelength = 768.0;
        /** Stretches fractal noise, which clusters around zero, so widths reach both ends of the range. */
        public double widthContrast = 2.0;
        /** Sand continues this far offshore as shallow sea floor. */
        public double offshore = 32.0;
        /** No beach on land rising more than this above the sea. */
        public double maxHeight = 6.0;
    }

    /** Bare rock and snow on high or steep ground. Heights are for a 300 block surface and scale with it. */
    public static final class Surface {
        /** Permanent snow line of each climate zone. Polar 0 means snow down to the sea. */
        public double snowLineTropical = 280.0;
        public double snowLineTemperate = 250.0;
        public double snowLineCold = 150.0;
        public double snowLinePolar = 0.0;
        /** The snow line varies by this fraction. */
        public double snowLineVariation = 0.05;
        public double snowLineWavelength = 512.0;
        /** Snow does not stay on faces steeper than this, in percent. */
        public double snowMaxSlope = 130.0;
        /** Ground steeper than this is bare rock, in percent. */
        public double rockSlope = 70.0;
        /** The rock slope threshold varies by this many percentage points. */
        public double rockSlopeVariation = 20.0;
        public double rockNoiseWavelength = 96.0;
        /** Land within this many blocks of the sea stays soil, however steep. */
        public double rockMinAboveSea = 12.0;
        /** From this fraction of the snow line up to the snow line, the rock slope falls to highRockSlope. */
        public double bareRockLine = 0.93;
        /** Rock slope at the snow line, in percent: thin high ground exposes rock on gentler faces. */
        public double highRockSlope = 45.0;
        public double rockPatchWavelength = 64.0;
        /** Noise level above which rock turns into the patch stone. */
        public double rockPatchThreshold = 0.1;
    }

    /** Biome ids of one climate zone, by landform. Several ids in a list form large patches of each. */
    public static final class BiomeZone {
        public String[] ocean = {"minecraft:ocean"};
        public String[] deepOcean = {"minecraft:deep_ocean"};
        public String[] beach = {"minecraft:beach"};
        public String[] dry = {"minecraft:plains"};
        public String[] medium = {"minecraft:forest"};
        public String[] wet = {"minecraft:forest"};
        /** Optional: wet lowland just above the sea. */
        public String[] swamp = {};
        public String[] highland = {"minecraft:forest"};
        /** Optional: dry highland away from zone borders. */
        public String[] dryHighland = {};
        public String[] alpine = {"minecraft:meadow"};
        public String[] snowPeak = {"minecraft:frozen_peaks"};

        BiomeZone with(String[] ocean, String[] deepOcean, String[] beach, String[] dry, String[] medium, String[] wet,
                       String[] swamp, String[] highland, String[] alpine, String[] snowPeak) {
            this.ocean = ocean;
            this.deepOcean = deepOcean;
            this.beach = beach;
            this.dry = dry;
            this.medium = medium;
            this.wet = wet;
            this.swamp = swamp;
            this.highland = highland;
            this.alpine = alpine;
            this.snowPeak = snowPeak;
            return this;
        }

        BiomeZone withDryHighland(String[] dryHighland) {
            this.dryHighland = dryHighland;
            return this;
        }

        String[][] lists() {
            return new String[][]{ocean, deepOcean, beach, dry, medium, wet, swamp, highland, dryHighland, alpine, snowPeak};
        }
    }

    public static final class Biomes {
        /** Size of the patches when a list holds several biomes. */
        public double patchWavelength = 1536.0;
        /** Lowland below this humidity counts as dry, at or above wetHumidity as wet. */
        public double dryHumidity = 0.35;
        public double wetHumidity = 0.65;
        public double swampMaxAboveSea = 5.0;
        /** Land this far inland from the coast and low enough is beach biome. */
        public double beachDepth = 48.0;
        /** Sea this much deeper than the surface uses the deep ocean biome. */
        public double deepOceanDepth = 18.0;
        /** Above this fraction of the snow line is the alpine band, where trees stop. */
        public double treeLine = 0.85;
        /** Highland starts this fraction of the way from the sea to the snow line. */
        public double highlandLine = 0.4;
        /** Blocks below the surface where cave biomes can start. */
        public double caveDepth = 48.0;
        public double caveWavelength = 1024.0;
        public double caveThreshold = 0.1;
        /** Cave biomes from dry to humid. */
        public String[] caves = {"minecraft:dripstone_caves", "minecraft:lush_caves"};

        public BiomeZone tropical = new BiomeZone().with(
                ids("warm_ocean"), ids("lukewarm_ocean", "deep_lukewarm_ocean"), ids("beach"),
                ids("desert", "badlands", "savanna"), ids("savanna", "sparse_jungle"), ids("jungle", "bamboo_jungle"),
                ids("swamp"), ids("savanna_plateau", "windswept_savanna"),
                ids("meadow"), ids("jagged_peaks")).withDryHighland(ids("wooded_badlands", "eroded_badlands"));
        public BiomeZone temperate = new BiomeZone().with(
                ids("ocean", "lukewarm_ocean"), ids("deep_ocean"), ids("beach", "stony_shore"),
                ids("plains", "sunflower_plains"), ids("forest", "birch_forest", "flower_forest"),
                ids("dark_forest", "old_growth_birch_forest"), ids("swamp"),
                ids("windswept_forest", "windswept_hills", "forest"),
                ids("meadow"), ids("frozen_peaks", "jagged_peaks"));
        public BiomeZone cold = new BiomeZone().with(
                ids("cold_ocean"), ids("deep_cold_ocean"), ids("stony_shore", "beach"),
                ids("taiga", "windswept_gravelly_hills"), ids("taiga", "old_growth_spruce_taiga"),
                ids("old_growth_pine_taiga", "old_growth_spruce_taiga"), new String[0],
                ids("grove", "windswept_hills"), ids("snowy_slopes"), ids("frozen_peaks", "snowy_slopes"));
        public BiomeZone polar = new BiomeZone().with(
                ids("frozen_ocean"), ids("deep_frozen_ocean"), ids("snowy_beach"),
                ids("snowy_plains", "ice_spikes"), ids("snowy_plains", "snowy_taiga"), ids("snowy_taiga", "grove"),
                new String[0], ids("snowy_slopes"), ids("snowy_slopes"), ids("frozen_peaks"));

        private static String[] ids(String... names) {
            String[] ids = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                ids[i] = "minecraft:" + names[i];
            }
            return ids;
        }

        /** Every biome id the world can use, without duplicates, in a stable order. */
        public List<String> allBiomes() {
            Set<String> all = new LinkedHashSet<>(List.of(caves));
            for (BiomeZone zone : List.of(tropical, temperate, cold, polar)) {
                for (String[] list : zone.lists()) {
                    all.addAll(List.of(list));
                }
            }
            return new ArrayList<>(all);
        }
    }

    public static final class Spawn {
        /** Search radius in coarse cells. */
        public int searchRings = 64;
        public double minAboveSea = 3.0;
        public double maxAboveSea = 40.0;
    }

    /** Drainage. Sizes are in coarse cells. */
    public static final class Rivers {
        /** Cell size of the routing grid; a multiple of general.cellSize that divides general.regionSize. */
        public int flowCellSize = 256;
        /** How far upstream of a cell catchments reach, in blocks. */
        public int flowLookAhead = 16384;
        public int streamCells = 20;
        public int tributaryCells = 150;
        public int trunkCells = 1000;
        /** A trunk with more tributaries than this is a major trunk. */
        public int majorTrunkTributaries = 5;
        /** Height noise drainage routes over, so water gathers instead of running in parallel lines on even slopes. */
        public double routeNoise = 4.0;
        public double routeWavelength = 512.0;
        /** Water at least this deep over the ground of a filled depression is a lake. */
        public double lakeMinDepth = 2.0;
        /** How far river nodes stray from their cell center, as a fraction of the cell. */
        public double nodeJitter = 0.6;
        /** Sideways swing of river courses, as a fraction of the distance between nodes. */
        public double meander = 0.14;
        public int curveSegments = 12;
        /** Rivers are found up to this many blocks away, which also bounds how far later steps can see them. */
        public int queryReach = 512;
        /** Rivers vary by this fraction of their width. */
        public double widthVariation = 0.1;
        public double streamWidthMin = 4.0;
        public double streamWidthMax = 8.0;
        public double streamDepthMin = 1.0;
        public double streamDepthMax = 2.0;
        public double tributaryWidth = 50.0;
        public double tributaryDepth = 5.0;
        public double trunkWidth = 100.0;
        public double trunkDepthMin = 8.0;
        public double trunkDepthMax = 10.0;
        public double majorTrunkWidth = 200.0;
        public double majorTrunkDepthMin = 15.0;
        public double majorTrunkDepthMax = 20.0;
        /** Narrower rivers still cut a channel this wide, or noise cells would lose them. */
        public double minChannelWidth = 6.0;
        /** Valley floor width as a multiple of the river width. */
        public double valleyFloorFactor = 3.0;
        /** Valley walls: land may rise this grade above the floor at first, then steeper by the curve per block. */
        public double valleyWallGrade = 0.15;
        public double valleyWallCurve = 0.003;
        public double valleyWallMaxGrade = 0.9;
        /** Beyond the valley floor, land leaves the river's own bank height over this many blocks. */
        public double valleyBankBlend = 96.0;
        /** River banks fade out over this many blocks of land height above the sea. */
        public double coastBlend = 6.0;
        /** Ridge texture fades in over this many blocks from a valley floor, plus this many per block of river width. */
        public double textureFadeBase = 160.0;
        public double textureFadePerWidth = 2.0;
        /** How far a river node moves toward the middle of its neighbors, rounding sharp bends. */
        public double nodeSmoothing = 0.6;
        /** Height of the valley floor above the water. */
        public double bankHeight = 1.0;
    }

    public static final class Climate {
        public double temperatureWavelength = 8192.0;
        /** Area shares of the zones; polar takes the remainder. */
        public double tropicalShare = 0.30;
        public double temperateShare = 0.35;
        public double coldShare = 0.25;
        /** Fine temperature noise that dithers zone borders. */
        public double zoneJitterWavelength = 128.0;
        public double zoneJitterAmplitude = 0.03;
        /** Temperature margin on each side of a zone border where dry biomes give way, about 300 blocks wide. */
        public double zoneTransition = 0.05;
        public double humidityWavelength = 6144.0;
        /**
         * Coast distances in blocks mapped to {@link #continentalness}, piecewise linear. The default values pass
         * through vanilla's category boundaries and stay above -1.05, where mushroom fields would start.
         */
        public double[] coastDistances = {-4096.0, -1024.0, 0.0, 64.0, 512.0, 2048.0, 4096.0};
        public double[] continentalness = {-1.0, -0.455, -0.19, -0.11, 0.03, 0.3, 0.8};
        /** Blocks below the surface per unit of vanilla's depth parameter. */
        public double depthBlocksPerUnit = 128.0;
    }

    public int cellsPerRegion() {
        return general.regionSize / general.cellSize;
    }

    /** Bottom of the world. */
    public int minBuildY() {
        return MIN_Y;
    }

    /** Total build height: from the bottom to a multiple of 16 above the highest possible surface. */
    public int buildHeight() {
        int top = (general.maxSurfaceHeight + BUILD_HEADROOM + 15) / 16 * 16;
        return top - MIN_Y;
    }

    /** Multiplier for heights designed for a 300 block maximum surface. */
    public double heightScale() {
        return general.maxSurfaceHeight / 300.0;
    }

    /** Throws IllegalArgumentException naming the first invalid value. */
    public WorldgenSettings validate() {
        require(general.cellSize > 0 && general.regionSize > 0 && general.regionSize % general.cellSize == 0,
                "general.regionSize must be a positive multiple of general.cellSize");
        require(general.maxSurfaceHeight > general.seaLevel, "general.maxSurfaceHeight must be above general.seaLevel");
        require(general.maxSurfaceHeight + BUILD_HEADROOM + 15 <= MAX_BUILD_TOP,
                "general.maxSurfaceHeight is too large for Minecraft's build limit of " + MAX_BUILD_TOP);
        require(general.regionCacheSize > 0, "general.regionCacheSize must be positive");
        require(rivers.flowCellSize > 0 && rivers.flowCellSize % general.cellSize == 0 && general.regionSize % rivers.flowCellSize == 0,
                "rivers.flowCellSize must be a multiple of general.cellSize and divide general.regionSize");
        require(rivers.flowLookAhead > 0, "rivers.flowLookAhead must be positive");
        require(rivers.curveSegments > 0, "rivers.curveSegments must be positive");
        require(rivers.queryReach >= 0 && rivers.queryReach <= general.regionSize - rivers.flowCellSize * 2,
                "rivers.queryReach must leave the rivers of neighboring regions in reach");
        require(rivers.streamCells < rivers.tributaryCells && rivers.tributaryCells < rivers.trunkCells,
                "river cell thresholds must rise from stream to trunk");
        require(general.softCapFraction > 0.0 && general.softCapFraction < 1.0, "general.softCapFraction must be in (0, 1)");
        require(continents.oceanRatio > 0.0 && continents.oceanRatio < 1.0, "continents.oceanRatio must be in (0, 1)");
        require(mountains.peakMin > 0.0 && mountains.peakMax >= mountains.peakMin,
                "mountains.peakMin must be positive and not above mountains.peakMax");
        require(mountains.widthMin > 0.0 && mountains.widthMax >= mountains.widthMin,
                "mountains.widthMin must be positive and not above mountains.widthMax");
        require(mountains.summitWidth >= 0.0 && mountains.summitWidth < mountains.widthMin,
                "mountains.summitWidth must be below mountains.widthMin");
        require(mountains.zoneCoverage > 0.0 && mountains.zoneCoverage < 1.0, "mountains.zoneCoverage must be in (0, 1)");
        require(slopes.plains > 0.0 && slopes.hills > 0.0 && slopes.plateau > 0.0 && slopes.mountain > 0.0,
                "slopes must be positive");
        require(climate.tropicalShare + climate.temperateShare + climate.coldShare < 1.0,
                "climate zone shares must leave room for the polar zone");
        require(biomes.caves.length > 0, "biomes.caves must not be empty");
        for (BiomeZone zone : List.of(biomes.tropical, biomes.temperate, biomes.cold, biomes.polar)) {
            String[][] lists = zone.lists();
            for (int i = 0; i < lists.length; i++) {
                require(lists[i].length > 0 || lists[i] == zone.swamp || lists[i] == zone.dryHighland,
                        "biome lists must not be empty, except swamp and dryHighland");
            }
        }
        require(beaches.maxWidth > beaches.minWidth, "beaches.maxWidth must be above beaches.minWidth");
        require(climate.coastDistances.length >= 2 && climate.coastDistances.length == climate.continentalness.length,
                "climate.coastDistances and climate.continentalness must have the same length of at least 2");
        for (int i = 1; i < climate.coastDistances.length; i++) {
            require(climate.coastDistances[i] > climate.coastDistances[i - 1], "climate.coastDistances must be ascending");
        }
        return this;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Parses and validates; fields missing from the JSON keep their defaults. */
    public static WorldgenSettings fromJson(String json) {
        try {
            WorldgenSettings settings = GSON.fromJson(json, WorldgenSettings.class);
            return (settings != null ? settings : new WorldgenSettings()).validate();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
