package com.mtm.worldgen.logic;

import java.util.Arrays;
import java.util.function.DoubleBinaryOperator;

/**
 * Per-seed statistics of a 2D field, sampled on a large fixed grid around the origin. Layers use quantiles to
 * turn noise into area fractions (e.g. "30% ocean") that hold for every seed. Deterministic for a given field.
 */
final class NoiseStats {
    private final double[] sorted;

    NoiseStats(DoubleBinaryOperator field, int samplesPerAxis, double spacing) {
        double[] values = new double[samplesPerAxis * samplesPerAxis];
        double origin = -samplesPerAxis / 2.0 * spacing;
        for (int i = 0; i < samplesPerAxis; i++) {
            for (int j = 0; j < samplesPerAxis; j++) {
                values[i * samplesPerAxis + j] = field.applyAsDouble(origin + i * spacing, origin + j * spacing);
            }
        }
        Arrays.sort(values);
        this.sorted = values;
    }

    /** Value below which the fraction q of samples lies. */
    double quantile(double q) {
        return sorted[(int) (Math.max(0.0, Math.min(1.0, q)) * (sorted.length - 1))];
    }
}
