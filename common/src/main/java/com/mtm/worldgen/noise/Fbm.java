package com.mtm.worldgen.noise;

/** Fractal sum of {@link ValueNoise} octaves, normalized to [-1, 1]. */
public final class Fbm {
    private final long[] octaveSeeds;
    private final double baseFrequency;
    private final double normalization;

    /**
     * @param seed           layer seed
     * @param octaves        number of octaves, each at double the frequency and half the amplitude
     * @param baseWavelength wavelength of the first octave, in the caller's coordinate units
     */
    public Fbm(long seed, int octaves, double baseWavelength) {
        this.octaveSeeds = new long[octaves];
        for (int i = 0; i < octaves; i++) {
            octaveSeeds[i] = Hashing.derive(seed, i);
        }
        this.baseFrequency = 1.0 / baseWavelength;
        this.normalization = 1.0 / (2.0 - Math.pow(0.5, octaves - 1));
    }

    public double sample(double x, double z) {
        double sum = 0.0;
        double frequency = baseFrequency;
        double amplitude = 1.0;
        for (long octaveSeed : octaveSeeds) {
            sum += amplitude * ValueNoise.sample(octaveSeed, x * frequency, z * frequency);
            frequency *= 2.0;
            amplitude *= 0.5;
        }
        return sum * normalization;
    }
}
