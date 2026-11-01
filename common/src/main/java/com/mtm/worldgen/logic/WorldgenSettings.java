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
    public final Islands islands = new Islands();
    public final Surface surface = new Surface();
    public final Spawn spawn = new Spawn();
    public final Plains plains = new Plains();
    public final Isolated isolated = new Isolated();
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
        public double plateSpacing = 27500.0;
        /** How far plate centers move from their grid cell center, as a fraction of the spacing. */
        public double plateJitter = 0.85;
        /** Fraction of plates that are open ocean instead of continent. */
        public double oceanPlateFraction = 0.18;
        /** Spacing of continent group sites in plate units: roughly how many plates wide a continent is. */
        public double groupSpacing = 2.6;
        public double warpWavelength = 10240.0;
        public double warpAmplitude = 6500.0;
        /** Strait half-width offset between continent groups in plate units; negative closes it into land. */
        public double straitMean = 0.02;
        public double straitVariation = 0.16;
        public double straitWavelength = 15360.0;
        public double detailWavelength = 5120.0;
        public double detailAmplitude = 0.06;
        /** Land rises this far above the sea in continent interiors, before the skeleton. */
        public double landRange = 72.0;
        public double oceanDepth = 36.0;
    }

    public static final class Mountains {
        /** Wavelength of the tier field that divides land into plains, hills and mountains. */
        public double zoneWavelength = 11264.0;
        /** Share of the world in the mountain tier. */
        public double zoneCoverage = 0.30;
        /** Within this many blocks of the coast, mountains give way to hills. */
        public double coastBand = 2400.0;
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
        /** Ranges blend between a steep igneous and a gentle wind-worn style, patch by patch at this wavelength. */
        public double styleWavelength = 6144.0;
        /** Height and width of a range relative to its nominal size, by style. */
        public double igneousHeight = 1.1;
        public double erodedHeight = 0.75;
        public double igneousWidth = 0.8;
        public double erodedWidth = 1.6;
        /** Mix between a rounded (0) and a sharp, concave (1) cross section, by style. */
        public double igneousSharpness = 0.6;
        public double erodedSharpness = 0.0;
        /** Strength of the ridge texture, by style. */
        public double igneousTexture = 1.3;
        public double erodedTexture = 0.5;
        /** Cliff bands of igneous ranges: height of one step on the 300 block scale, patch size, and how sharp a step is. */
        public double cliffStep = 26.0;
        public double cliffWavelength = 640.0;
        public double cliffStrength = 0.92;
        public double cliffWidth = 0.05;
        /** Noise levels over which cliff patches fade in. */
        public double cliffLow = 0.12;
        public double cliffHigh = 0.26;
        /** Steepest slope of wind-worn ranges, as a share of the mountain slope limit. */
        public double erodedGrade = 0.6;
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

    /** The plains tier: wide lowlands for big cities. */
    public static final class Plains {
        /** Share of the world in the plains tier. */
        public double coverage = 0.33;
        public double fade = 0.06;
        /** Share of hills and plateaus that remains on plains. */
        public double reliefShare = 0.2;
    }

    public static final class Plateaus {
        public double zoneWavelength = 9216.0;
        public double coverage = 0.07;
        public double fade = 0.2;
        public double minHeight = 40.0;
        public double maxHeight = 80.0;
    }

    /** Isolated peaks where hills meet mountains, and dome hills inside the hill tier. */
    public static final class Isolated {
        /** One candidate per square of this size, in blocks. */
        public double spacing = 2048.0;
        public double peakChance = 0.1;
        public double peakMin = 50.0;
        public double peakMax = 90.0;
        /** Base radius as a multiple of the height. */
        public double peakSpread = 2.2;
        public double domeChance = 0.3;
        public double domeMin = 14.0;
        public double domeMax = 34.0;
        public double domeSpread = 6.0;
    }

    /** The hill tier, between plains and mountains. */
    public static final class Hills {
        public double height = 44.0;
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
        /** Coast types change along the shore at this wavelength. The remainder after these shares is sea cliffs. */
        public double typeWavelength = 3072.0;
        public double sandShare = 0.5;
        public double gravelShare = 0.2;
        public double rockShare = 0.2;
        /** Sea cliffs: height on the 300 block scale, how fast they rise inland, and how far they fall away behind. */
        public double cliffHeight = 14.0;
        public double cliffRise = 96.0;
        public double cliffFade = 300.0;
    }

    /** Islands in open water. */
    public static final class Islands {
        /** One candidate per square of this size, in blocks. */
        public double spacing = 2500.0;
        public double chance = 0.6;
        public double sandShare = 0.5;
        /** Rocky islands; the rest are mushroom islands. */
        public double rockShare = 0.35;
        public double radiusMin = 150.0;
        public double radiusMax = 400.0;
        /** Shores wander by this share of the radius, at this wavelength. */
        public double shoreVariation = 0.45;
        public double shoreWavelength = 256.0;
        /** Island heights above the sea, on the 300 block scale. */
        public double sandHeight = 4.0;
        public double rockHeight = 14.0;
        /** Islands keep this far from land, mushroom islands further. */
        public double landMargin = 400.0;
        public double mushroomMargin = 1000.0;
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
        /** Rock strata show on faces steeper than this grade, in percent, or they spread into wide rings on gentle slopes. */
        public double strataSlope = 160.0;
        /** Height of a layer in blocks, and how far layers wander up and down. */
        public double strataThickness = 6.0;
        public double strataWarp = 8.0;
        public double strataWavelength = 1024.0;
        public double rockPatchWavelength = 64.0;
        /** Noise level above which rock turns into the patch stone. */
        public double rockPatchThreshold = 0.1;
    }

    /** Biome ids of one climate zone, by landform. Several ids in a list form large patches of each. */
    public static final class BiomeZone {
        public String[] ocean = {"minecraft:ocean"};
        public String[] deepOcean = {"minecraft:deep_ocean"};
        public String[] beach = {"minecraft:beach"};
        /** Rocky coasts and rocky islands. */
        public String[] rockyShore = {"minecraft:stony_shore"};
        public String[] river = {"minecraft:river"};
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

        BiomeZone withRiver(String[] river) {
            this.river = river;
            return this;
        }

        BiomeZone withDryHighland(String[] dryHighland) {
            this.dryHighland = dryHighland;
            return this;
        }

        String[][] lists() {
            return new String[][]{ocean, deepOcean, beach, rockyShore, river, dry, medium, wet, swamp, highland, dryHighland, alpine, snowPeak};
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
        /** Biome of mushroom islands. */
        public String mushroomIsland = "minecraft:mushroom_fields";

        public BiomeZone tropical = new BiomeZone().with(
                ids("warm_ocean"), ids("lukewarm_ocean", "deep_lukewarm_ocean"), ids("beach"),
                ids("desert", "badlands", "savanna"), ids("savanna", "sparse_jungle"), ids("jungle", "bamboo_jungle"),
                ids("swamp", "mangrove_swamp"), ids("savanna_plateau", "windswept_savanna"),
                ids("meadow"), ids("jagged_peaks")).withDryHighland(ids("wooded_badlands", "eroded_badlands"));
        public BiomeZone temperate = new BiomeZone().with(
                ids("ocean", "lukewarm_ocean"), ids("deep_ocean"), ids("beach"),
                ids("plains", "sunflower_plains"), ids("forest", "birch_forest", "flower_forest"),
                ids("dark_forest", "old_growth_birch_forest"), ids("swamp"),
                ids("windswept_forest", "windswept_hills", "forest", "cherry_grove"),
                ids("meadow"), ids("frozen_peaks", "jagged_peaks"));
        public BiomeZone cold = new BiomeZone().with(
                ids("cold_ocean"), ids("deep_cold_ocean"), ids("beach"),
                ids("taiga", "snowy_taiga"), ids("taiga", "old_growth_spruce_taiga"),
                ids("old_growth_pine_taiga", "old_growth_spruce_taiga"), new String[0],
                ids("grove", "windswept_hills", "windswept_gravelly_hills"), ids("snowy_slopes"), ids("frozen_peaks", "snowy_slopes")).withRiver(ids("frozen_river"));
        public BiomeZone polar = new BiomeZone().with(
                ids("frozen_ocean"), ids("deep_frozen_ocean"), ids("snowy_beach"),
                ids("snowy_plains", "ice_spikes"), ids("snowy_plains", "snowy_taiga"), ids("snowy_taiga", "grove"),
                new String[0], ids("snowy_slopes"), ids("snowy_slopes"), ids("frozen_peaks")).withRiver(ids("frozen_river"));

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
            all.add(mushroomIsland);
            for (BiomeZone zone : List.of(tropical, temperate, cold, polar)) {
                for (String[] list : zone.lists()) {
                    all.addAll(List.of(list));
                }
            }
            return new ArrayList<>(all);
        }
    }

    public static final class Spawn {
        /** Rings searched outward from the world center, and the distance between them in coarse cells. */
        public int searchRings = 128;
        public int searchStep = 8;
        public double minAboveSea = 3.0;
        public double maxAboveSea = 40.0;
    }

    /** Drainage. Sizes are in coarse cells. */
    public static final class Rivers {
        /** Cell size of the routing grid; a multiple of general.cellSize that divides general.regionSize. */
        public int flowCellSize = 256;
        /** How far upstream of a cell catchments reach, in blocks. */
        public int flowLookAhead = 32768;
        public int streamCells = 64;
        public int tributaryCells = 150;
        public int trunkCells = 1000;
        /** A trunk with more tributaries than this is a major trunk. */
        public int majorTrunkTributaries = 5;
        /** Height noise drainage routes over, so water gathers instead of running in parallel lines on even slopes. */
        public double routeNoise = 4.0;
        public double routeWavelength = 512.0;
        /** Water at least this deep over the ground of a filled depression is a lake. */
        public double lakeMinDepth = 2.0;
        /** Lake outlets of at most this many nodes that drop at least this far into other water are left out. */
        public int shortOutletLength = 6;
        public double shortOutletDrop = 8.0;
        /** How far river nodes stray from their cell center, as a fraction of the cell. */
        public double nodeJitter = 0.6;
        /** Sideways swing of river courses, as a fraction of the distance between nodes. */
        public double meander = 0.14;
        /** Meanders fade on rivers wider than this. */
        public double meanderWidth = 100.0;
        public int curveSegments = 12;
        /** Rivers are found up to this many blocks away, which also bounds how far later steps can see them. */
        public int queryReach = 768;
        /** Rivers vary by this fraction of their width. */
        /** Nodes before the sea over which rivers settle down to sea level, so that they do not fall into it. */
        public int estuaryLength = 5;
        public double widthVariation = 0.1;
        /** A side branch widens by this factor over its last edge, rounding the corners where it meets the main river. */
        public double mouthFlare = 1.6;
        /** Distance over which a river's width wanders along its length. */
        public double widthWavelength = 2048.0;
        /** Rivers on a slope of at most gentleRiverSlope are plainWidthFactor times their base width, those at
         * steepRiverSlope or more steepWidthFactor times, with a smooth change between. */
        public double gentleRiverSlope = 0.01;
        public double steepRiverSlope = 0.12;
        public double plainWidthFactor = 2.0;
        public double steepWidthFactor = 0.5;
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
        /** Floodplains flatten low land beside rivers: this many blocks wide, plus this many per block of river width. */
        public double floodplainBase = 64.0;
        public double floodplainPerWidth = 5.0;
        /** Plains stay within this many blocks above the river. */
        public double floodplainRise = 5.0;
        /** Land this far above the river is no plain and stays as it is, however close. */
        public double floodplainRelief = 40.0;
        /** River banks fade out over this many blocks of land height above the sea. */
        public double coastBlend = 6.0;
        /** Ridge texture fades in over this many blocks from a valley floor, plus this many per block of river width. */
        public double textureFadeBase = 64.0;
        public double textureFadePerWidth = 2.0;
        /** How far a river node moves toward the middle of its neighbors, rounding sharp bends. */
        public double nodeSmoothing = 0.6;
        /** River nodes averaged on each side when smoothing big rivers. */
        public int trunkSmoothing = 2;
        public int majorTrunkSmoothing = 4;
        /** Height of the valley floor above the water. */
        public double bankHeight = 1.0;
    }

    public static final class Climate {
        public double temperatureWavelength = 16384.0;
        /** Area shares of the zones; polar takes the remainder. */
        public double tropicalShare = 0.30;
        public double temperateShare = 0.35;
        public double coldShare = 0.25;
        /** Fine temperature noise that dithers zone borders. */
        public double zoneJitterWavelength = 128.0;
        public double zoneJitterAmplitude = 0.03;
        /** Temperature margin on each side of a zone border where dry biomes give way, about 300 blocks wide. */
        public double zoneTransition = 0.05;
        public double humidityWavelength = 12288.0;
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

    /** Every build height a valid configuration can produce, from the lowest world to the build limit. */
    public static List<Integer> possibleBuildHeights() {
        List<Integer> heights = new ArrayList<>();
        for (int top = 16; top <= MAX_BUILD_TOP; top += 16) {
            heights.add(top - MIN_Y);
        }
        return heights;
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
        require(beaches.sandShare + beaches.gravelShare + beaches.rockShare <= 1.0, "coast type shares must not exceed 1");
        require(islands.sandShare + islands.rockShare <= 1.0, "island shares must not exceed 1");
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
        require(plains.coverage >= 0.0 && plains.coverage + mountains.zoneCoverage < 1.0, "plains.coverage plus mountains.zoneCoverage must leave room for hills");
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
