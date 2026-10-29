package com.mtm.worldgen.logic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainLogicTest {
    private static final long SEED = 1234567890123L;
    private static final WorldgenSettings SETTINGS = WorldgenSettings.DEFAULT;

    @Test
    void regionsDoNotDependOnComputationOrder() {
        TerrainLogic forward = new TerrainLogic(SEED, SETTINGS);
        TerrainLogic shuffled = new TerrainLogic(SEED, SETTINGS);

        List<int[]> coords = new ArrayList<>();
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                coords.add(new int[]{rx, rz});
            }
        }
        List<int[]> reordered = new ArrayList<>(coords);
        Collections.shuffle(reordered, new Random(42));

        for (int[] c : coords) {
            forward.region(c[0], c[1]);
        }
        for (int[] c : reordered) {
            shuffled.region(c[0], c[1]);
        }
        for (int[] c : coords) {
            assertTrue(forward.region(c[0], c[1]).contentEquals(shuffled.region(c[0], c[1])),
                    "region " + c[0] + "," + c[1] + " differs between computation orders");
        }
    }

    @Test
    void regionsDoNotDependOnThreads() throws Exception {
        TerrainLogic sequential = new TerrainLogic(SEED, SETTINGS);
        TerrainLogic concurrent = new TerrainLogic(SEED, SETTINGS);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<LogicRegion>> futures = new ArrayList<>();
            // Several threads request the same regions at once
            for (int i = 0; i < 32; i++) {
                int rx = i % 4 - 2;
                int rz = i / 8 - 2;
                futures.add(pool.submit(() -> concurrent.region(rx, rz)));
            }
            for (Future<LogicRegion> future : futures) {
                LogicRegion region = future.get();
                LogicRegion expected = sequential.region(region.regionX(), region.regionZ());
                assertTrue(expected.contentEquals(region), "region differs when computed concurrently");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void surfaceIsContinuousAcrossRegionBorders() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int border = SETTINGS.regionSize();
        double maxStep = 0.0;
        for (int z = -border; z <= border; z += 7) {
            double west = logic.sampleSurfaceHeight(border - 1, z);
            double east = logic.sampleSurfaceHeight(border, z);
            maxStep = Math.max(maxStep, Math.abs(east - west));
        }
        // One block apart: anything above a block or two would be a visible seam
        assertTrue(maxStep < 1.0, "height jumps by " + maxStep + " across a region border");
    }

    @Test
    void sameSeedGivesSameSurface() {
        TerrainLogic a = new TerrainLogic(SEED, SETTINGS);
        TerrainLogic b = new TerrainLogic(SEED, SETTINGS);
        for (int i = 0; i < 1000; i++) {
            int x = i * 37 - 18000;
            int z = i * 53 - 26000;
            assertEquals(a.sampleSurfaceHeight(x, z), b.sampleSurfaceHeight(x, z));
        }
    }

    @Test
    void oceanCoversRoughlyTheConfiguredRatio() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int ocean = 0;
        int total = 0;
        double sea = SETTINGS.seaLevel() - 1;
        for (int x = -40000; x < 40000; x += 400) {
            for (int z = -40000; z < 40000; z += 400) {
                if (logic.sampleSurfaceHeight(x, z) < sea) {
                    ocean++;
                }
                total++;
            }
        }
        double ratio = (double) ocean / total;
        assertTrue(Math.abs(ratio - SETTINGS.oceanRatio()) < 0.08, "ocean ratio was " + ratio);
    }
}
