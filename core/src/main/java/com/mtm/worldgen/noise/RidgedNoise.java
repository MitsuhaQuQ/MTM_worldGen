package com.mtm.worldgen.noise;

/**
 * Ridged fractal noise in [0, 1]: each octave is (1 - |value noise|)^2, which has sharp crests where the
 * underlying noise crosses zero. Anisotropic: coordinates are scaled separately along two axes.
 */
public final class RidgedNoise {
    private final long[] octaveSeeds;
    private final double normalization;

    public RidgedNoise(long seed, int octaves) {
        this.octaveSeeds = new long[octaves];
        for (int i = 0; i < octaves; i++) {
            octaveSeeds[i] = Hashing.derive(seed, i);
        }
        this.normalization = 1.0 / (2.0 - Math.pow(0.5, octaves - 1));
    }

    /** Samples at noise-space coordinates; the first octave has wavelength 1 in both axes. */
    public double sample(double u, double v) {
        double sum = 0.0;
        double frequency = 1.0;
        double amplitude = 1.0;
        for (long octaveSeed : octaveSeeds) {
            double crest = 1.0 - Math.abs(ValueNoise.sample(octaveSeed, u * frequency, v * frequency));
            sum += amplitude * crest * crest;
            frequency *= 2.0;
            amplitude *= 0.5;
        }
        return sum * normalization;
    }
}
