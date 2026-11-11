package com.mtm.worldgen.logic;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiverWaterRegressionTest {
    private static TerrainLogic screenshotWorld() {
        WorldgenSettings settings = WorldgenSettings.fromJson(WorldgenSettings.DEFAULT.toJson());
        settings.general.maxSurfaceHeight = 1024;
        settings.general.designHeight = 1024;
        // The world these places come from predates narrow rivers on plateaus
        settings.rivers.plateauMaxWidth = 0.0;
        settings.rivers.flowLookAhead = 32768;
        return new TerrainLogic(7701471443861534004L, settings);
    }

    @Test
    void erosionCreekReachesTheActualReceivingChannel() {
        TerrainLogic logic = screenshotWorld();
        int minX = 16915;
        int minZ = -5245;
        int size = 56;
        boolean[] wet = new boolean[size * size];
        int[] tops = WaterSurface.sample(minX, minZ, size, logic::sampleWaterLevel);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double water = logic.sampleWaterLevel(minX + x, minZ + z);
                wet[z * size + x] = !Double.isNaN(water)
                        && logic.sampleSurfaceHeight(minX + x, minZ + z) < water
                        && tops[(z + 1) * (size + 2) + x + 1] != WaterSurface.DRY;
            }
        }
        int start = (-5236 - minZ) * size + 16926 - minX;
        assertTrue(wet[start], "the upstream creek must hold water");
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        wet[start] = false;
        boolean reaches = false;
        while (!queue.isEmpty()) {
            int index = queue.remove();
            int x = index % size;
            int z = index / size;
            reaches |= minZ + z >= -5200;
            for (int[] d : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (nx >= 0 && nz >= 0 && nx < size && nz < size && wet[nz * size + nx]) {
                    wet[nz * size + nx] = false;
                    queue.add(nz * size + nx);
                }
            }
        }
        assertTrue(reaches, "the erosion creek stops behind the receiving river's dry bank");
    }

    @Test
    void highCreekDoesNotRaiseTheLakeShoreIntoATerrace() {
        TerrainLogic logic = screenshotWorld();
        double lake = logic.sampleLakeLevel(17382, -5404);
        assertTrue(!Double.isNaN(lake));
        double floodplainRise = logic.settings().rivers.bankHeight + logic.settings().rivers.floodplainRise;
        assertTrue(logic.sampleSurfaceHeight(17314, -5456) <= lake + floodplainRise,
                "the narrow high creek raises the broad lake shore into a terrace");
    }

    @Test
    void oxbowWaterFitsItsCarvedBanksInsteadOfStandingAboveThem() {
        TerrainLogic logic = new TerrainLogic(-699474053823044315L, WorldgenSettings.DEFAULT);
        assertTrue(Double.isNaN(logic.sampleWaterLevel(11600, -8250)),
                "the shallow rim must stay dry instead of filling above its ground");
        double water = logic.sampleWaterLevel(11600, -8550);
        assertTrue(!Double.isNaN(water), "the oxbow must still hold water in its hollow");
        assertTrue(logic.sampleSurfaceHeight(11600, -8550) < water);
        assertTrue(logic.sampleSurfaceHeight(11600, -8250) >= water,
                "the oxbow must lie below the ground along its shallow rim");
    }

    @Test
    void wideChannelKeepsItsOwnLevelBesideAHigherStream() {
        WorldgenSettings.Rivers config = WorldgenSettings.DEFAULT.rivers;
        double[] segments = {
                -100, 0, 100, 0, 200, 200, 8, 8, 100, 100, 1, 1, 0, 0, 0, 0,
                -100, 84, 100, 84, 6, 6, 1, 1, 180, 180, 1, 1, 0, 0, 0, 0
        };
        RiverRegion rivers = new RiverRegion(-256, -256, 512, 1, config, 64, 2,
                segments, new byte[]{3, 1}, new int[]{-1, -1}, new int[][]{{0, 1}}, new double[0], new double[0], 120);
        // Inside the trunk but just outside the nearby high stream, which used to dominate the water-level blend.
        assertEquals(100.0, rivers.nearest(0, 80).level(), 1e-9);
        assertEquals(180.0, rivers.nearest(0, 84).level(), 1e-9);
    }

    /**
     * A creek on a flat coast runs across the beach into the sea (seed 837238560036030884, 2176 -1664): the roughness
     * erosion starts from put the beach a block or two below the water, the creek ended at that cell, and its water
     * stopped on the sand tens of blocks short of the shore.
     */
    @Test
    void coastalCreekRunsIntoTheSea() {
        TerrainLogic logic = TerrainLogic.forWorld(837238560036030884L, WorldgenSettings.DEFAULT);
        double sea = logic.seaSurface();
        assertTrue(logic.sampleWaterLevel(2221, -1700) > logic.sampleSurfaceHeight(2221, -1700), "the creek holds water");
        // Along the creek's water from there, until it meets the sea
        java.util.Set<Long> seen = new java.util.HashSet<>();
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.add(new int[]{2221, -1700});
        seen.add(key(2221, -1700));
        boolean reachesSea = false;
        while (!open.isEmpty() && !reachesSea) {
            int[] at = open.poll();
            for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = at[0] + step[0];
                int z = at[1] + step[1];
                if (x < 2000 || x > 2300 || z < -1730 || z > -1500 || !seen.add(key(x, z))) {
                    continue;
                }
                double surface = logic.sampleSurfaceHeight(x, z);
                if (surface < sea - 1.0) {
                    reachesSea = true;
                    break;
                }
                if (logic.sampleWaterLevel(x, z) > surface) {
                    open.add(new int[]{x, z});
                }
            }
        }
        assertTrue(reachesSea, "the creek's water ends before the sea");
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}
