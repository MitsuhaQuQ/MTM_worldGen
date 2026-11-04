package com.mtm.worldgen.logic;

import java.util.function.DoubleSupplier;

/** Stretches vanilla ore heights above 0 to fill a taller world, with the same ore per block of rock. */
public final class OreHeights {
    private static final int[] NONE = new int[0];

    private OreHeights() {
    }

    /**
     * Heights for one vanilla ore attempt. Heights up to 0 stay as they are. Above 0, each vanilla height stands for
     * a span of the factor's size, and a span that size holds that many attempts, which is what keeps the density.
     *
     * @param y      height sampled from the vanilla range
     * @param factor how much taller the world is than vanilla, at least 1
     * @param random uniform values from 0 to 1
     */
    public static int[] stretch(int y, double factor, DoubleSupplier random) {
        if (y <= 0) {
            return new int[]{y};
        }
        int count = (int) factor + (random.getAsDouble() < factor - (int) factor ? 1 : 0);
        if (count == 0) {
            return NONE;
        }
        int[] heights = new int[count];
        for (int i = 0; i < count; i++) {
            heights[i] = 1 + (int) Math.floor((y - 1 + random.getAsDouble()) * factor);
        }
        return heights;
    }
}
