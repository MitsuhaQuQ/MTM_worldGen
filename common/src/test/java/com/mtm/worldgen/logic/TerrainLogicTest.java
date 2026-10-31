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
                highest = Math.max(highest, logic.sampleLandform(x, z));
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
            // River banks are channel structure, steeper than any slope limit by design
            RiverRegion.Hit river = logic.sampleRiver(x, z);
            if (river != null && river.distance() < 1.5 * river.halfWidth(SETTINGS.rivers.minChannelWidth)) {
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
    void drainageDoesNotDependOnRegionOrder() {
        TerrainLogic forward = new TerrainLogic(SEED, SETTINGS);
        TerrainLogic backward = new TerrainLogic(SEED, SETTINGS);
        FlowRegion first = forward.flowRegion(0, 0);
        forward.flowRegion(1, 0);
        FlowRegion second = forward.flowRegion(1, 0);
        backward.flowRegion(1, 0);
        assertTrue(first.contentEquals(backward.flowRegion(0, 0)), "region 0,0 depends on the order");
        assertTrue(second.contentEquals(backward.flowRegion(1, 0)), "region 1,0 depends on the order");
    }

    /** Catchments only grow downstream, and a river leaving a region must not shrink in the next one. */
    @Test
    void riversKeepTheirSizeAcrossRegionBorders() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int cells = logic.flowRegion(0, 0).cells();
        int crossings = 0;
        for (int rx = -6; rx < 6; rx++) {
            for (int rz = -6; rz < 6; rz++) {
                FlowRegion west = logic.flowRegion(rx, rz);
                FlowRegion east = logic.flowRegion(rx + 1, rz);
                FlowRegion south = logic.flowRegion(rx, rz + 1);
                for (int i = 1; i < cells - 1; i++) {
                    if (west.flows(cells - 1, i) && west.downstreamX(cells - 1, i) == 1
                            && west.catchment(cells - 1, i) >= SETTINGS.rivers.tributaryCells) {
                        assertKeepsSize(west.catchment(cells - 1, i), east.catchment(0, i + west.downstreamZ(cells - 1, i)));
                        crossings++;
                    }
                    if (west.flows(i, cells - 1) && west.downstreamZ(i, cells - 1) == 1
                            && west.catchment(i, cells - 1) >= SETTINGS.rivers.tributaryCells) {
                        assertKeepsSize(west.catchment(i, cells - 1), south.catchment(i + west.downstreamX(i, cells - 1), 0));
                        crossings++;
                    }
                }
            }
        }
        assertTrue(crossings > 10, "only " + crossings + " rivers cross borders");
    }

    private static void assertKeepsSize(int upstream, int downstream) {
        assertTrue(downstream >= 0.8 * upstream, "river shrinks from " + upstream + " to " + downstream);
    }

    /** Both regions at a border must see exactly the same rivers, or a curve went missing on one side. */
    @Test
    void riverCoursesAreContinuousAcrossRegionBorders() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int border = SETTINGS.general.regionSize;
        RiverRegion west = logic.riverRegion(0, 0);
        RiverRegion east = logic.riverRegion(1, 0);
        int hits = 0;
        for (int z = 1; z < border; z += 11) {
            RiverRegion.Hit a = west.nearest(border, z);
            RiverRegion.Hit b = east.nearest(border, z);
            assertEquals(a, b, "regions disagree at z " + z);
            hits += a != null ? 1 : 0;
        }
        assertTrue(hits > 0, "no rivers near the border");
    }

    @Test
    void riverCoursesDoNotDependOnThreads() throws Exception {
        TerrainLogic sequential = new TerrainLogic(SEED, SETTINGS);
        TerrainLogic concurrent = new TerrainLogic(SEED, SETTINGS);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<RiverRegion>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int rx = i % 2;
                futures.add(pool.submit(() -> concurrent.riverRegion(rx, 0)));
            }
            for (int i = 0; i < futures.size(); i++) {
                assertTrue(sequential.riverRegion(i % 2, 0).contentEquals(futures.get(i).get()), "rivers differ when computed concurrently");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Beside a channel the ground must reach the water surface, or the water would stand as a wall. */
    @Test
    void riversStayBetweenTheirBanks() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        Random random = new Random(11);
        double sea = SETTINGS.general.seaLevel - 1;
        List<Double> excess = new ArrayList<>();
        for (int i = 0; i < 200000 && excess.size() < 2000; i++) {
            int x = random.nextInt(16000) - 8000;
            int z = random.nextInt(16000) - 8000;
            RiverRegion.Hit river = logic.sampleRiver(x, z);
            if (river == null || river.level() <= sea + 2) {
                continue;
            }
            double half = river.halfWidth(SETTINGS.rivers.minChannelWidth);
            // Lake beds lie below the water by design, so only dry columns count as banks
            if (river.distance() >= half && river.distance() < 2.0 * half && Double.isNaN(logic.sampleWaterLevel(x, z))) {
                excess.add(river.level() - logic.sampleSurfaceHeight(x, z));
            }
        }
        Collections.sort(excess);
        double p99 = excess.get((int) (0.99 * (excess.size() - 1)));
        System.out.println("water over the bank p99 " + p99 + " max " + excess.get(excess.size() - 1) + " of " + excess.size());
        assertTrue(excess.size() > 50, "too few river samples: " + excess.size());
        assertTrue(p99 <= 2.0, "99th percentile of water over the bank is " + p99);
    }

    /** A lake must keep its basin wherever rivers run in: this is what used to cut rivers off at the shore. */
    @Test
    void lakesKeepTheirBasinsNextToRivers() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        int size = SETTINGS.rivers.flowCellSize;
        int lakes = 0;
        for (int rx = -2; rx <= 2; rx++) {
            for (int rz = -2; rz <= 2; rz++) {
                FlowRegion flow = logic.flowRegion(rx, rz);
                int cells = flow.cells();
                for (int lz = 1; lz < cells - 1; lz++) {
                    for (int lx = 1; lx < cells - 1; lx++) {
                        if (flow.isLake(lx, lz) && flow.isLake(lx - 1, lz) && flow.isLake(lx + 1, lz) && flow.isLake(lx, lz - 1) && flow.isLake(lx, lz + 1)
                                && flow.isLake(lx - 1, lz - 1) && flow.isLake(lx + 1, lz - 1) && flow.isLake(lx - 1, lz + 1) && flow.isLake(lx + 1, lz + 1)) {
                            double x = (rx * cells + lx + 0.5) * size;
                            double z = (rz * cells + lz + 0.5) * size;
                            double water = logic.sampleWaterLevel(x, z);
                            assertTrue(!Double.isNaN(water) && logic.sampleSurfaceHeight(x, z) < water, "dry lake center at " + x + "," + z);
                            lakes++;
                        }
                    }
                }
            }
        }
        assertTrue(lakes > 0, "no lake centers found");
    }

    /** Where a river's route ends in the sea, the real surface there must be sea, with nothing closing off the mouth. */
    @Test
    void riversEndInOpenSea() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        double sea = SETTINGS.general.seaLevel - 1;
        long nodeSeed = RiverLayer.nodeSeed(SEED);
        int mouths = 0;
        int blocked = 0;
        for (int rx = -3; rx < 3; rx++) {
            for (int rz = -3; rz < 3; rz++) {
                FlowRegion flow = logic.flowRegion(rx, rz);
                int cells = flow.cells();
                for (int lz = 1; lz < cells - 1; lz++) {
                    for (int lx = 1; lx < cells - 1; lx++) {
                        if (!flow.flows(lx, lz) || flow.riverClass(lx, lz) == FlowRegion.RiverClass.NONE) {
                            continue;
                        }
                        int nx = lx + flow.downstreamX(lx, lz);
                        int nz = lz + flow.downstreamZ(lx, lz);
                        if (flow.flows(nx, nz) || flow.isLake(nx, nz)) {
                            continue;
                        }
                        double[] node = RiverLayer.node(nodeSeed, SETTINGS.rivers, rx * cells + nx, rz * cells + nz);
                        mouths++;
                        blocked += logic.sampleSurfaceHeight(node[0], node[1]) > sea ? 1 : 0;
                    }
                }
            }
        }
        System.out.println("river mouths " + mouths + " blocked " + blocked);
        assertTrue(mouths > 10, "too few mouths: " + mouths);
        assertTrue(blocked <= mouths / 20, blocked + " of " + mouths + " river mouths end on land");
    }

    /** Low land beside a big river is a plain within a few blocks of it, and never below it. */
    @Test
    void lowlandsBesideBigRiversArePlains() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        Random random = new Random(13);
        List<Double> rises = new ArrayList<>();
        for (int i = 0; i < 400000 && rises.size() < 1000; i++) {
            int x = random.nextInt(32000) - 16000;
            int z = random.nextInt(32000) - 16000;
            RiverRegion.Hit river = logic.sampleRiver(x, z);
            // Ground near the coast stays low by design, so that nothing closes off river mouths
            if (river == null || river.level() < SETTINGS.general.seaLevel + SETTINGS.rivers.coastBlend + 4.0
                    || river.width() < 100.0 || river.run() > 150.0 || river.run() <= 0.0
                    || logic.sampleLandform(x, z) > river.level() + 15.0 || !Double.isNaN(logic.sampleWaterLevel(x, z))) {
                continue;
            }
            rises.add(logic.sampleSurfaceHeight(x, z) - river.level());
        }
        Collections.sort(rises);
        assertTrue(rises.size() > 100, "too few samples: " + rises.size());
        double p95 = rises.get((int) (0.95 * (rises.size() - 1)));
        double p05 = rises.get((int) (0.05 * (rises.size() - 1)));
        System.out.println("plain rise above rivers p05 " + p05 + " p95 " + p95 + " of " + rises.size());
        assertTrue(p95 <= SETTINGS.rivers.floodplainRise + SETTINGS.rivers.bankHeight + 1.0, "plains stand " + p95 + " above rivers");
        assertTrue(p05 >= 0.0, "plains lie " + p05 + " below rivers");
    }

    /** Channels carry river biomes, which freeze in the cold zones and stay open elsewhere. */
    @Test
    void riversCarryRiverBiomes() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        double sea = SETTINGS.general.seaLevel - 1;
        int checked = 0;
        for (int rx = -3; rx < 3; rx++) {
            for (int rz = -3; rz < 3; rz++) {
                RiverRegion rivers = logic.riverRegion(rx, rz);
                for (int i = 0; i < rivers.segments(); i += 7) {
                    double x = rivers.startX(i);
                    double z = rivers.startZ(i);
                    RiverRegion.Hit river = logic.sampleRiver(x, z);
                    if (river == null || river.distance() > 1.0 || logic.sampleSurfaceHeight(x, z) < sea + 1.0 || !Double.isNaN(logic.sampleLakeLevel(x, z))) {
                        continue;
                    }
                    String biome = logic.sampleBiome(x, z);
                    String zone = logic.sampleClimateZone(x, z);
                    boolean cold = zone.equals("COLD") || zone.equals("POLAR");
                    assertEquals(cold ? "minecraft:frozen_river" : "minecraft:river", biome, "in zone " + zone + " at " + x + "," + z);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "only " + checked + " river points checked");
    }

    @Test
    void waterNeverRunsUphill() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        for (int rx = -1; rx <= 1; rx++) {
            for (int rz = -1; rz <= 1; rz++) {
                FlowRegion flow = logic.flowRegion(rx, rz);
                int cells = flow.cells();
                for (int lz = 0; lz < cells; lz++) {
                    for (int lx = 0; lx < cells; lx++) {
                        if (flow.flows(lx, lz)) {
                            int nx = lx + flow.downstreamX(lx, lz);
                            int nz = lz + flow.downstreamZ(lx, lz);
                            if (nx >= 0 && nz >= 0 && nx < cells && nz < cells) {
                                assertTrue(flow.waterLevel(nx, nz) <= flow.waterLevel(lx, lz), "water rises at " + lx + "," + lz);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void landDrainsWithoutSinks() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        FlowRegion flow = logic.flowRegion(0, 0);
        int cells = flow.cells();
        int size = SETTINGS.general.regionSize / cells;
        double sea = SETTINGS.general.seaLevel - 1;
        for (int lz = 0; lz < cells; lz++) {
            for (int lx = 0; lx < cells; lx++) {
                double height = logic.sampleSurfaceHeight((lx + 0.5) * size, (lz + 0.5) * size);
                // Routing samples coarsely, so cells right at the coast can count as sea
                assertTrue(flow.flows(lx, lz) || height < sea + 16.0, "land sink at height " + height);
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
        for (int x = -160000; x < 160000; x += 1600) {
            for (int z = -160000; z < 160000; z += 1600) {
                if (logic.sampleContinentHeight(x, z) < sea) {
                    ocean++;
                }
                total++;
            }
        }
        double ratio = (double) ocean / total;
        assertTrue(Math.abs(ratio - SETTINGS.continents.oceanRatio) < 0.08, "ocean ratio was " + ratio);
    }
}
