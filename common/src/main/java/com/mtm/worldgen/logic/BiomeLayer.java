package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

/**
 * Pipeline step 8: biome assignment from terrain and climate. Water and coast come first, then altitude bands
 * relative to the local snow line, then lowlands by climate zone and humidity. Biomes are large and follow the
 * terrain, so they support its features instead of covering them with a mosaic.
 */
final class BiomeLayer {
    private static final long VARIANT_SALT = 0x56415249L;
    private static final long CAVE_SALT = 0x43415645L;

    private final WorldgenSettings settings;
    private final WorldgenSettings.Biomes config;
    private final Fbm variantNoise;
    private final Fbm caveNoise;

    BiomeLayer(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.config = settings.biomes;
        this.variantNoise = new Fbm(Hashing.derive(seed, VARIANT_SALT), 2, config.patchWavelength);
        this.caveNoise = new Fbm(Hashing.derive(seed, CAVE_SALT), 2, config.caveWavelength);
    }

    /**
     * @param height        surface height
     * @param coastDistance distance to the coast in blocks, positive inland
     * @param snowLine      local snow line height; zero or less means snow reaches the sea
     * @param humidity      humidity in [0, 1]
     * @param river         whether the position lies in a river channel
     * @param rockyCoast    whether the coast here is rock or cliff rather than a beach
     * @param island        kind of the island here, or null
     * @param zoneBorder    whether the position is close to a neighboring climate zone, where dry biomes such as
     *                      desert and badlands give way so their strong colors do not meet the next zone head on
     */
    String surfaceBiome(double x, double z, ClimateLayer.Zone zone, double height, double coastDistance,
                        double snowLine, double humidity, boolean river, boolean rockyCoast,
                        ContinentLayer.IslandKind island, boolean zoneBorder) {
        double sea = settings.general.seaLevel - 1;
        boolean dry = humidity < config.dryHumidity && !zoneBorder;
        WorldgenSettings.BiomeZone biomes = zoneBiomes(zone);
        if (height < sea) {
            return pick(sea - height > config.deepOceanDepth ? biomes.deepOcean : biomes.ocean, x, z);
        }
        if (island == ContinentLayer.IslandKind.MUSHROOM) {
            return config.mushroomIsland;
        }
        if (river) {
            return pick(biomes.river, x, z);
        }
        if (coastDistance < config.beachDepth && height < sea + settings.beaches.maxHeight) {
            return pick(rockyCoast ? biomes.rockyShore : biomes.beach, x, z);
        }
        if (snowLine > 0.0) {
            if (height >= snowLine) {
                return pick(biomes.snowPeak, x, z);
            }
            if (height >= config.treeLine * snowLine) {
                return pick(biomes.alpine, x, z);
            }
            if (height >= sea + config.highlandLine * (snowLine - sea)) {
                return pick(dry && biomes.dryHighland.length > 0 ? biomes.dryHighland : biomes.highland, x, z);
            }
        }
        if (humidity >= config.wetHumidity && height < sea + config.swampMaxAboveSea && biomes.swamp.length > 0) {
            return pick(biomes.swamp, x, z);
        }
        if (dry) {
            return pick(biomes.dry, x, z);
        }
        return pick(humidity >= config.wetHumidity ? biomes.wet : biomes.medium, x, z);
    }

    /** Whether a position deep underground belongs to a cave biome rather than the surface biome above it. */
    boolean isCave(double x, double z) {
        return caveNoise.sample(x, z) > config.caveThreshold;
    }

    String caveBiome(double humidity) {
        int index = Math.min(config.caves.length - 1, (int) (humidity * config.caves.length));
        return config.caves[index];
    }

    private WorldgenSettings.BiomeZone zoneBiomes(ClimateLayer.Zone zone) {
        return switch (zone) {
            case POLAR -> config.polar;
            case COLD -> config.cold;
            case TEMPERATE -> config.temperate;
            case TROPICAL -> config.tropical;
        };
    }

    /** Chooses from a list by low-frequency noise, stretched because fractal noise clusters around zero. */
    private String pick(String[] biomes, double x, double z) {
        if (biomes.length == 1) {
            return biomes[0];
        }
        double noise = Math.max(-1.0, Math.min(1.0, 2.0 * variantNoise.sample(x, z)));
        int index = (int) ((0.5 + 0.5 * noise) * biomes.length);
        return biomes[Math.min(index, biomes.length - 1)];
    }
}
