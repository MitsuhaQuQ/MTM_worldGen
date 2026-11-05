package com.mtm.worldgen.logic;

import org.junit.jupiter.api.Test;

import java.util.function.DoubleBinaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterSurfaceTest {
    @Test
    void neighboringWindowsAgreeIncludingTheirCleanedBorders() {
        DoubleBinaryOperator levels = (x, z) -> {
            if (x == -1 && z == 4 || x == 15 && z == 8 || z == 16 && x == 5) {
                return Double.NaN;
            }
            return z < 0 || x < 0 ? 384.7 : 352.2;
        };
        int size = 16;
        int span = size + 2;
        int[] whole = WaterSurface.sample(-16, -16, 48, levels);
        for (int cz = -16; cz <= 16; cz += size) {
            for (int cx = -16; cx <= 16; cx += size) {
                int[] chunk = WaterSurface.sample(cx, cz, size, levels);
                for (int z = -1; z <= size; z++) {
                    for (int x = -1; x <= size; x++) {
                        assertEquals(whole[(cz + z + 17) * 50 + cx + x + 17], chunk[(z + 1) * span + x + 1],
                                "water differs at " + (cx + x) + "," + (cz + z));
                    }
                }
            }
        }
    }

    @Test
    void preservesDesignedPoolAndLakeLevelsAcrossAHeightStep() {
        int[] tops = WaterSurface.sample(-8, -8, 16, (x, z) -> z < 0 ? 384.71 : 352.25);
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                assertEquals(z - 8 < 0 ? 384 : 352, tops[(z + 1) * 18 + x + 1]);
            }
        }
    }

    @Test
    void notchAtAFallTakesTheLowerWaterInsteadOfCreatingARaisedColumn() {
        int[] tops = WaterSurface.sample(0, 0, 16,
                (x, z) -> x == 8 && z == 8 ? Double.NaN : x < 8 ? 384.0 : 352.0);
        assertEquals(352, tops[9 * 18 + 9]);
    }
}
