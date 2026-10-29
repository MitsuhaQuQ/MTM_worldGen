package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.util.Arrays;

/**
 * Pipeline step 1: continent and ocean layout. A pure function of seed + block coordinates.
 * The land/ocean threshold is chosen per seed so the sampled ocean fraction matches the configured ratio.
 */
final class ContinentLayer {
    private static final long SALT = 0x434F4E54494EL;
    private static final int OCTAVES = 5;
    private static final double BASE_WAVELENGTH = 8192.0;

    private static final int THRESHOLD_SAMPLES_PER_AXIS = 128;
    private static final double THRESHOLD_SAMPLE_SPACING = 512.0;

    /** Land rises this far above sea level at the continent's noise maximum (mountains come later). */
    private static final double LAND_RANGE = 72.0;
    private static final double OCEAN_DEPTH = 36.0;

    private final Fbm noise;
    private final WorldgenSettings settings;
    private final double threshold;

    ContinentLayer(long seed, WorldgenSettings settings) {
        this.noise = new Fbm(Hashing.derive(seed, SALT), OCTAVES, BASE_WAVELENGTH);
        this.settings = settings;
        this.threshold = estimateThreshold();
    }

    double value(double x, double z) {
        return noise.sample(x, z);
    }

    double threshold() {
        return threshold;
    }

    /**
     * Surface height of the continent layer at a block position. Continuous and with finite slope at the
     * coastline: the coarse grid samples this function, so any jump or steep kink there shows up as
     * grid-aligned stair steps along the coast.
     */
    double height(double x, double z) {
        double c = value(x, z);
        double sea = settings.seaLevel() - 1;
        if (c >= threshold) {
            return sea + LAND_RANGE * (c - threshold) / (1.0 - threshold);
        }
        return sea - OCEAN_DEPTH * (threshold - c) / (threshold + 1.0);
    }

    /** Quantile of the noise over a large fixed sample grid; deterministic for a given seed. */
    private double estimateThreshold() {
        int n = THRESHOLD_SAMPLES_PER_AXIS;
        double[] values = new double[n * n];
        double origin = -n / 2.0 * THRESHOLD_SAMPLE_SPACING;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                values[i * n + j] = value(origin + i * THRESHOLD_SAMPLE_SPACING, origin + j * THRESHOLD_SAMPLE_SPACING);
            }
        }
        Arrays.sort(values);
        return values[(int) (settings.oceanRatio() * (values.length - 1))];
    }
}
