package com.mtm.worldgen.logic;

import java.util.function.DoubleBinaryOperator;

/** Block water columns, with a one-column border for repairing interpolation errors along their banks. */
public final class WaterSurface {
    public static final int DRY = Integer.MIN_VALUE;

    private WaterSurface() {
    }

    /**
     * Rounds single-column notches and spikes without changing the level of existing water. Waterfalls and lake
     * levels belong to the terrain logic: lowering water afterwards leaves its already carved bed and banks behind.
     * Read two border columns so the returned border is cleaned by exactly the same rule as the interior. Overlapping
     * calls therefore agree, regardless of chunk boundaries or generation order.
     */
    public static int[] sample(int startX, int startZ, int size, DoubleBinaryOperator levels) {
        int sourceSpan = size + 4;
        int[] source = new int[sourceSpan * sourceSpan];
        for (int z = 0; z < sourceSpan; z++) {
            for (int x = 0; x < sourceSpan; x++) {
                double level = levels.applyAsDouble(startX + x - 2, startZ + z - 2);
                source[z * sourceSpan + x] = Double.isNaN(level) ? DRY : (int) Math.floor(level);
            }
        }
        int span = size + 2;
        int[] result = new int[span * span];
        int[] offsets = {-1, 1, -sourceSpan, sourceSpan};
        for (int z = 0; z < span; z++) {
            for (int x = 0; x < span; x++) {
                int index = (z + 1) * sourceSpan + x + 1;
                int wet = 0;
                int lowest = Integer.MAX_VALUE;
                for (int offset : offsets) {
                    int neighbor = source[index + offset];
                    if (neighbor != DRY) {
                        wet++;
                        lowest = Math.min(lowest, neighbor);
                    }
                }
                int level = source[index];
                result[z * span + x] = level != DRY && wet <= 1 ? DRY
                        : level == DRY && wet >= 3 ? lowest : level;
            }
        }
        return result;
    }
}
