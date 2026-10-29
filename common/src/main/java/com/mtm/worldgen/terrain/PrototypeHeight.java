package com.mtm.worldgen.terrain;

import com.mtm.worldgen.noise.Hashing;
import com.mtm.worldgen.noise.ValueNoise;

/**
 * Placeholder surface height used to validate the generation pipeline.
 * Will be replaced by the coarse-grid terrain logic.
 */
public final class PrototypeHeight {
    private static final long LARGE_SALT = 0x4C41524745L;
    private static final long SMALL_SALT = 0x534D414C4CL;

    private PrototypeHeight() {
    }

    public static double height(long seed, int x, int z) {
        double large = ValueNoise.sample(Hashing.derive(seed, LARGE_SALT), x / 512.0, z / 512.0);
        double small = ValueNoise.sample(Hashing.derive(seed, SMALL_SALT), x / 128.0, z / 128.0);
        return 64.0 + 56.0 * (0.65 * large + 0.35 * small);
    }
}
