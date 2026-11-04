package com.mtm.worldgen.world;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Carries the world seed into custom density functions while vanilla compiles them. Density functions are compiled
 * lazily, the first time RandomState#getSampler asks for one, and the compile context does not hand out the seed, so a
 * mixin brackets that call with {@link #begin} and {@link #end}. Calls may nest, so the seeds form a stack.
 */
public final class SeedCapture {
    private static final ThreadLocal<Deque<Long>> SEEDS = ThreadLocal.withInitial(ArrayDeque::new);

    private SeedCapture() {
    }

    public static void begin(long seed) {
        SEEDS.get().push(seed);
    }

    public static void end() {
        SEEDS.get().poll();
    }

    /** The seed of the density functions currently being compiled on this thread, or null outside compilation. */
    public static Long current() {
        return SEEDS.get().peek();
    }

    /** The terrain logic for the seed being compiled, or the seedless stand-in that unbound placeholders sample. */
    static com.mtm.worldgen.logic.TerrainLogic logic(com.mtm.worldgen.logic.WorldgenSettings settings) {
        Long seed = current();
        return com.mtm.worldgen.logic.TerrainLogic.forWorld(seed != null ? seed : 0L, settings);
    }
}
