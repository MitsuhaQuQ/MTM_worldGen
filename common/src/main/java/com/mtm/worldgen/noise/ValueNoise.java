package com.mtm.worldgen.noise;

/** 2D value noise on an integer lattice with smoothstep interpolation, range [-1, 1]. */
public final class ValueNoise {
    private ValueNoise() {
    }

    public static double sample(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fz = smooth(z - z0);

        double v00 = lattice(seed, x0, z0);
        double v10 = lattice(seed, x0 + 1, z0);
        double v01 = lattice(seed, x0, z0 + 1);
        double v11 = lattice(seed, x0 + 1, z0 + 1);

        double a = v00 + (v10 - v00) * fx;
        double b = v01 + (v11 - v01) * fx;
        return a + (b - a) * fz;
    }

    private static double lattice(long seed, int x, int z) {
        return Hashing.unit(Hashing.hash(seed, x, z)) * 2.0 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
