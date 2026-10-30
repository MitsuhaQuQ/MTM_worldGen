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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        int border = SETTINGS.general.regionSize;
        double maxStep = 0.0;
        for (int z = -border; z <= border; z += 7) {
            double west = logic.sampleSurfaceHeight(border - 1, z);
            double east = logic.sampleSurfaceHeight(border, z);
            maxStep = Math.max(maxStep, Math.abs(east - west));
        }
        // One block apart, so the step is the grade: anything beyond twice the steepest allowed slope is a seam
        double limit = 2.0 * SETTINGS.slopes.mountain / 100.0;
        assertTrue(maxStep < limit, "height jumps by " + maxStep + " across a region border");
    }

    @Test
    void surfaceStaysBelowMaximumAndHasMountains() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        double highest = 0.0;
        for (int x = -20000; x < 20000; x += 64) {
            for (int z = -20000; z < 20000; z += 64) {
                highest = Math.max(highest, logic.sampleSurfaceHeight(x, z));
            }
        }
        assertTrue(highest <= SETTINGS.general.maxSurfaceHeight, "surface reaches " + highest);
        assertTrue(highest > SETTINGS.general.maxSurfaceHeight / 2.0, "no tall mountains, highest is " + highest);
    }

    /**
     * Grades stay within the mountain limit for all but rare spots where several profiles stack, and nowhere
     * reach twice the limit, which would mean a discontinuity rather than a steep slope.
     */
    @Test
    void slopesStayWithinLimits() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        double limit = SETTINGS.slopes.mountain / 100.0;
        Random random = new Random(7);
        List<Double> grades = new ArrayList<>();
        double sea = SETTINGS.general.seaLevel - 1;
        for (int i = 0; i < 40000; i++) {
            int x = random.nextInt(16000) - 8000;
            int z = random.nextInt(16000) - 8000;
            double h = logic.sampleSurfaceHeight(x, z);
            if (h < sea) {
                continue;
            }
            double gx = logic.sampleSurfaceHeight(x + 1, z) - h;
            double gz = logic.sampleSurfaceHeight(x, z + 1) - h;
            grades.add(Math.hypot(gx, gz));
        }
        Collections.sort(grades);
        double p999 = grades.get((int) (0.999 * (grades.size() - 1)));
        double max = grades.get(grades.size() - 1);
        assertTrue(p999 <= limit, "99.9th percentile grade " + p999 + " exceeds " + limit);
        assertTrue(max < 2.0 * limit, "max grade " + max + " suggests a discontinuity");
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
    void spawnIsOnLandForManySeeds() {
        double sea = SETTINGS.general.seaLevel - 1;
        for (long seed = 0; seed < 20; seed++) {
            TerrainLogic logic = new TerrainLogic(seed * 0x9E3779B97F4A7C15L, SETTINGS);
            BlockColumn spawn = logic.findSpawn();
            assertTrue(logic.sampleSurfaceHeight(spawn.x(), spawn.z()) > sea + 1.0,
                    "seed " + seed + " spawns at " + spawn + " below or at sea level");
        }
    }

    @Test
    void rockAndSnowFollowHeightAndSlope() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        WorldgenSettings.Surface surface = SETTINGS.surface;
        double sea = SETTINGS.general.seaLevel - 1;
        int rockCount = 0;
        for (int x = -8000; x < 8000; x += 37) {
            for (int z = -8000; z < 8000; z += 37) {
                double height = logic.sampleSurfaceHeight(x, z);
                if (logic.sampleSnow(x, z)) {
                    assertTrue(height >= sea, "snow under the sea at height " + height);
                }
                if (logic.sampleRock(x, z)) {
                    rockCount++;
                    assertTrue(height >= sea + surface.rockMinAboveSea, "rock at height " + height);
                }
            }
        }
        assertTrue(rockCount > 0, "mountains should expose rock");
    }

    @Test
    void tropicalAndColdZonesNeverTouch() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int step = 512;
        for (int x = -30000; x < 30000; x += step) {
            for (int z = -30000; z < 30000; z += step) {
                String here = logic.sampleClimateZone(x, z);
                String east = logic.sampleClimateZone(x + step, z);
                String south = logic.sampleClimateZone(x, z + step);
                assertTrue(!touch(here, east) && !touch(here, south), "tropical next to cold at " + x + "," + z);
            }
        }
    }

    private static boolean touch(String a, String b) {
        return a.equals("TROPICAL") && (b.equals("COLD") || b.equals("POLAR"))
                || b.equals("TROPICAL") && (a.equals("COLD") || a.equals("POLAR"));
    }

    @Test
    void biomesFollowAltitudeAndWater() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        double sea = SETTINGS.general.seaLevel - 1;
        for (int x = -8000; x < 8000; x += 97) {
            for (int z = -8000; z < 8000; z += 97) {
                String biome = logic.sampleBiome(x, z);
                double height = logic.sampleSurfaceHeight(x, z);
                if (height < sea) {
                    assertTrue(biome.contains("ocean"), biome + " under the sea at " + x + "," + z);
                } else {
                    assertTrue(!biome.contains("ocean"), biome + " on land at " + x + "," + z);
                }
            }
        }
    }

    @Test
    void settingsSurviveJsonAndKeepDefaultsForMissingFields() {
        WorldgenSettings partial = WorldgenSettings.fromJson("{\"general\": {\"maxSurfaceHeight\": 500}}");
        assertEquals(500, partial.general.maxSurfaceHeight);
        assertEquals(SETTINGS.general.seaLevel, partial.general.seaLevel);
        assertEquals(SETTINGS.mountains.peakMax, partial.mountains.peakMax);
        assertEquals(partial.toJson(), WorldgenSettings.fromJson(partial.toJson()).toJson());
        assertEquals(0, partial.buildHeight() % 16, "build height must be a multiple of 16");
        assertTrue(partial.minBuildY() + partial.buildHeight() > 500, "build range must contain the surface");
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WorldgenSettings.fromJson("{\"general\": {\"cellSize\": 63}}"));
        assertThrows(IllegalArgumentException.class,
                () -> WorldgenSettings.fromJson("{\"general\": {\"maxSurfaceHeight\": 40}}"));
        assertThrows(IllegalArgumentException.class, () -> WorldgenSettings.fromJson("{not json"));
    }

    @Test
    void oceanCoversRoughlyTheConfiguredRatio
() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int ocean = 0;
        int total = 0;
        double sea = SETTINGS.general.seaLevel - 1;
        for (int x = -40000; x < 40000; x += 400) {
            for (int z = -40000; z < 40000; z += 400) {
                if (logic.sampleSurfaceHeight(x, z) < sea) {
                    ocean++;
                }
                total++;
            }
        }
        double ratio = (double) ocean / total;
        assertTrue(Math.abs(ratio - SETTINGS.continents.oceanRatio) < 0.08, "ocean ratio was " + ratio);
    }
}
