package com.mtm.worldgen.logic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

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
    public final Spawn spawn = new Spawn();
    public final Climate climate = new Climate();

    public static final class General {
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
        public double plateSpacing = 8192.0;
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
        public double textureShare = 0.15;
        public double textureAcross = 640.0;
        public double textureAlong = 1920.0;
        /** Share of the slope limit used by the main profile; the rest is headroom for texture and foothills. */
        public double profileSlopeShare = 0.45;
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

    public static final class Spawn {
        /** Search radius in coarse cells. */
        public int searchRings = 64;
        public double minAboveSea = 3.0;
        public double maxAboveSea = 40.0;
    }

    public static final class Climate {
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
        require(general.softCapFraction > 0.0 && general.softCapFraction < 1.0, "general.softCapFraction must be in (0, 1)");
        require(continents.oceanRatio > 0.0 && continents.oceanRatio < 1.0, "continents.oceanRatio must be in (0, 1)");
        require(mountains.peakMin > 0.0 && mountains.peakMax >= mountains.peakMin,
                "mountains.peakMin must be positive and not above mountains.peakMax");
        require(mountains.zoneCoverage > 0.0 && mountains.zoneCoverage < 1.0, "mountains.zoneCoverage must be in (0, 1)");
        require(slopes.plains > 0.0 && slopes.hills > 0.0 && slopes.plateau > 0.0 && slopes.mountain > 0.0,
                "slopes must be positive");
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
