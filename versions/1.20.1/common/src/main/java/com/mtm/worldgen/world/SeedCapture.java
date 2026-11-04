package com.mtm.worldgen.world;

/**
 * Carries the world seed into custom density functions while vanilla builds the noise router.
 * The seed only exists inside RandomState#create, so a mixin
 * brackets that call with {@link #begin} and {@link #end}.
 */
public final class SeedCapture {
    private static final ThreadLocal<Long> SEED = new ThreadLocal<>();

    private SeedCapture() {
    }

    public static void begin(long seed) {
        SEED.set(seed);
    }

    public static void end() {
        SEED.remove();
    }

    /** The seed of the router currently being built on this thread, or null outside router creation. */
    public static Long current() {
        return SEED.get();
    }
}
