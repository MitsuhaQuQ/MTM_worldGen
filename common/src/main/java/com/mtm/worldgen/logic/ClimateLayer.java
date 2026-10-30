package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

/**
 * Pipeline step 2: temperature and humidity. Climate zones are bands of one continuous temperature field, ordered
 * polar, cold, temperate, tropical, so tropical and cold can never touch and polar only borders cold. Zone
 * boundaries are per-seed quantiles, so zone area shares hold for every seed.
 */
final class ClimateLayer {
    enum Zone {
        POLAR, COLD, TEMPERATE, TROPICAL
    }

    private static final long TEMPERATURE_SALT = 0x54454D50L;
    private static final long JITTER_SALT = 0x4A495454L;
    private static final long HUMIDITY_SALT = 0x48554D49L;

    private static final int STATS_SAMPLES_PER_AXIS = 96;
    private static final double STATS_SPACING = 1536.0;
    /** Quantiles mapped to humidity 0 and 1, so the range is fully used for every seed. */
    private static final double HUMIDITY_LOW_QUANTILE = 0.05;
    private static final double HUMIDITY_HIGH_QUANTILE = 0.95;

    private final WorldgenSettings.Climate config;
    private final WorldgenSettings.Surface surface;
    private final Fbm temperature;
    private final Fbm jitter;
    private final Fbm humidity;
    private final double polarEnd;
    private final double coldEnd;
    private final double temperateEnd;
    private final double humidityLow;
    private final double humidityHigh;
    /** Temperature at the middle of each zone, polar first: snow lines blend between these. */
    private final double[] zoneCenters;

    ClimateLayer(long seed, WorldgenSettings settings) {
        this.config = settings.climate;
        this.surface = settings.surface;
        this.temperature = new Fbm(Hashing.derive(seed, TEMPERATURE_SALT), 3, config.temperatureWavelength);
        this.jitter = new Fbm(Hashing.derive(seed, JITTER_SALT), 2, config.zoneJitterWavelength);
        this.humidity = new Fbm(Hashing.derive(seed, HUMIDITY_SALT), 3, config.humidityWavelength);

        double polarShare = 1.0 - config.tropicalShare - config.temperateShare - config.coldShare;
        NoiseStats temperatures = new NoiseStats(temperature::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.polarEnd = temperatures.quantile(polarShare);
        this.coldEnd = temperatures.quantile(polarShare + config.coldShare);
        this.temperateEnd = temperatures.quantile(polarShare + config.coldShare + config.temperateShare);
        this.zoneCenters = new double[]{
                temperatures.quantile(polarShare / 2.0),
                temperatures.quantile(polarShare + config.coldShare / 2.0),
                temperatures.quantile(polarShare + config.coldShare + config.temperateShare / 2.0),
                temperatures.quantile(1.0 - config.tropicalShare / 2.0)};

        NoiseStats humidities = new NoiseStats(humidity::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.humidityLow = humidities.quantile(HUMIDITY_LOW_QUANTILE);
        this.humidityHigh = humidities.quantile(HUMIDITY_HIGH_QUANTILE);
    }

    /** Fine jitter dithers zone borders instead of leaving a clean line, and never crosses a whole zone. */
    double temperature(double x, double z) {
        return temperature.sample(x, z) + config.zoneJitterAmplitude * jitter.sample(x, z);
    }

    Zone zone(double temperature) {
        if (temperature < polarEnd) {
            return Zone.POLAR;
        }
        if (temperature < coldEnd) {
            return Zone.COLD;
        }
        return temperature < temperateEnd ? Zone.TEMPERATE : Zone.TROPICAL;
    }

    /** Whether a temperature lies within the transition of a border between two zones. */
    boolean nearZoneBorder(double temperature) {
        double margin = config.zoneTransition;
        return Math.abs(temperature - polarEnd) < margin || Math.abs(temperature - coldEnd) < margin
                || Math.abs(temperature - temperateEnd) < margin;
    }

    /** Humidity in [0, 1]. */
    double humidity(double x, double z) {
        double value = (humidity.sample(x, z) - humidityLow) / (humidityHigh - humidityLow);
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** Snow line on the 300 block design scale, blended between zone centers so it has no steps at zone borders. */
    double snowLine(double temperature) {
        double[] lines = {surface.snowLinePolar, surface.snowLineCold, surface.snowLineTemperate, surface.snowLineTropical};
        if (temperature <= zoneCenters[0]) {
            return lines[0];
        }
        for (int i = 1; i < lines.length; i++) {
            if (temperature <= zoneCenters[i]) {
                double t = (temperature - zoneCenters[i - 1]) / (zoneCenters[i] - zoneCenters[i - 1]);
                return lines[i - 1] + t * (lines[i] - lines[i - 1]);
            }
        }
        return lines[lines.length - 1];
    }
}
