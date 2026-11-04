package com.mtm.worldgen.noise;

/**
 * Stateless coordinate hashing. Every random value in terrain generation must come from here
 * (seed + coordinates), never from stateful RNGs, so results do not depend on evaluation order.
 */
public final class Hashing {
    private Hashing() {
    }

    /** SplitMix64 finalizer. */
    public static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }

    public static long hash(long seed, int x, int z) {
        return mix(seed ^ mix(x * 0x9E3779B97F4A7C15L ^ mix(z * 0xC2B2AE3D27D4EB4FL)));
    }

    /** Maps a hash to [0, 1). */
    public static double unit(long hash) {
        return (hash >>> 11) * 0x1.0p-53;
    }

    /** Derives an independent seed for a named generation layer. */
    public static long derive(long seed, long salt) {
        return mix(seed + mix(salt));
    }
}
