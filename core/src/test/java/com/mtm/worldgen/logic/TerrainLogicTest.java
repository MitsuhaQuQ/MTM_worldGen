package com.mtm.worldgen.logic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainLogicTest {
    private static final long SEED = 1234567890123L;
    private static final WorldgenSettings SETTINGS = WorldgenSettings.DEFAULT;
    /**
     * One logic for the tests that only sample the default world, so each erosion region is computed once for all of
     * them rather than once per test. Tests that compare logics, depend on the order regions are computed in, or set
     * foreign chunks build their own. Its caches hold every region the tests visit: with the usual 64, tests sampling
     * different places at once push each other's regions out, and the run gets slower than with a logic each.
     */
    private static final TerrainLogic SHARED = new TerrainLogic(SEED, largeCaches());

    private static WorldgenSettings largeCaches() {
        WorldgenSettings settings = WorldgenSettings.fromJson(SETTINGS.toJson());
        settings.general.regionCacheSize = 1024;
        return settings;
    }

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
        TerrainLogic logic = SHARED;
        int border = SETTINGS.general.regionSize;
        double maxStep = 0.0;
        for (int z = -border; z <= border; z += 7) {
            if (logic.sampleCliffShare(border, z) > 0.0) {
                continue;
            }
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
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
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
            // Cliffs, sea stacks and the walls of sinkholes are exempt from slope limits by design, and a grade spans a
            // column and its neighbors
            if (logic.sampleCliffShare(x, z) > 0.0 || logic.sampleCliffShare(x + 1, z) > 0.0 || logic.sampleCliffShare(x, z + 1) > 0.0
                    || logic.inSinkhole(x, z) || logic.inSinkhole(x + 1, z) || logic.inSinkhole(x, z + 1)) {
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

    /** Overhangs stay rare, leave the height map alone, and never pass the height that bounds chunk filling. */
    @Test
    void overhangsStayBoundedAndRare() {
        TerrainLogic logic = SHARED;
        Random random = new Random(11);
        int columns = 0;
        int overhangs = 0;
        for (int i = 0; i < 60000; i++) {
            int x = random.nextInt(30000) - 15000;
            int z = random.nextInt(30000) - 15000;
            double surface = logic.surfaceHeight(x, z);
            columns += surface >= SETTINGS.general.seaLevel ? 1 : 0;
            assertEquals(logic.sampleSurfaceHeight(x, z), surface);
            if (!logic.hasOverhangs(x, z)) {
                // Above the ground, where a sinkhole's chamber below cannot reach
                assertEquals(surface, logic.solidHeight(x, (int) surface + 8, z));
                continue;
            }
            overhangs++;
            double top = logic.highestSolid(x, z);
            for (int y = (int) surface - 60; y <= top + 8; y += 4) {
                assertTrue(logic.solidHeight(x, y, z) <= top, "overhang above its bound at " + x + "," + z);
            }
        }
        assertTrue(overhangs > 0, "no overhangs");
        assertTrue(overhangs < 0.03 * columns, overhangs + " of " + columns + " land columns have overhangs");
    }

    /** A sinkhole sinks the ground to its floor, with air in its chamber below the height map. */
    @Test
    void sinkholesOpenIntoChambers() {
        TerrainLogic logic = SHARED;
        int found = 0;
        for (int cellX = -16; cellX < 16; cellX++) {
            for (int cellZ = -16; cellZ < 16; cellZ++) {
                double[] pit = logic.sinkholePit(cellX, cellZ);
                if (pit == null) {
                    continue;
                }
                found++;
                int x = (int) Math.floor(pit[0]);
                int z = (int) Math.floor(pit[2]);
                double floor = logic.surfaceHeight(x, z);
                assertTrue(floor <= pit[1] + 1.0, "pit floor " + floor + " above " + pit[1]);
                int below = (int) Math.floor(floor) - 4;
                assertTrue(logic.solidHeight(x, below, z) < below, "no chamber below the pit at " + x + "," + z);
            }
        }
        assertTrue(found > 0, "no sinkholes");
    }

    /** Inland sinks hold water below their rim and take in no land higher than themselves as a dry hole. */
    @Test
    void inlandSinksHoldWater() {
        int sinks = 0;
        for (long seed : new long[]{SEED, 2L, 3L}) {
            TerrainLogic logic = new TerrainLogic(seed, SETTINGS);
            int size = SETTINGS.rivers.flowCellSize;
            for (int regionX = -4; regionX < 4; regionX++) {
                for (int regionZ = -4; regionZ < 4; regionZ++) {
                    FlowRegion flow = logic.flowRegion(regionX, regionZ);
                    for (int lx = 0; lx < flow.cells(); lx++) {
                        for (int lz = 0; lz < flow.cells(); lz++) {
                            if (flow.isSink(lx, lz)) {
                                sinks++;
                                double x = (regionX * flow.cells() + lx + 0.5) * size;
                                double z = (regionZ * flow.cells() + lz + 0.5) * size;
                                assertTrue(logic.sampleSurfaceHeight(x, z) < flow.lakeLevel(lx, lz), "dry sink at " + x + "," + z);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(sinks > 0, "no inland sinks");
    }

    /** A big merged map has keys that collide badly as plain Longs, and its snapshot has to load in no time anyway. */
    @Test
    void foreignMapHandlesLargeMaps() {
        Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
        for (int x = -500; x < 500; x++) {
            for (int z = -500; z < 500; z++) {
                chunks.put(ForeignMap.key(x, z), ForeignMap.INNER);
            }
        }
        ForeignMap map = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> new ForeignMap(chunks, SETTINGS));
        assertTrue(map.contains(-500, 499));
        assertTrue(!map.contains(500, 0));
        assertEquals(chunks.size(), map.chunks().size());
    }

    /** Land has to meet a foreign edge next to a river too: rivers reach far, and land held back by one leaves a wall. */
    @Test
    void landMeetsForeignChunksNextToRivers() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        Random random = new Random(5);
        int tried = 0;
        short ground = 170;
        short[] heights = new short[ForeignMap.SAMPLES];
        short[] water = new short[ForeignMap.SAMPLES];
        Arrays.fill(heights, ground);
        Arrays.fill(water, ForeignMap.NO_WATER);
        for (int i = 0; i < 8000 && tried < 12; i++) {
            int x = random.nextInt(16000) - 8000;
            int z = random.nextInt(16000) - 8000;
            RiverRegion.Hit river = logic.sampleRiver(x, z);
            if (river == null || river.texture() > 0.5 || river.distance() < 2.0 * river.halfWidth(SETTINGS.rivers.minChannelWidth)
                    || logic.sampleSurfaceHeight(x, z) < SETTINGS.general.seaLevel) {
                continue;
            }
            tried++;
            double[] plain = new double[64];
            for (int j = 0; j < plain.length; j++) {
                plain[j] = logic.sampleWaterLevel(x + 8 * (j % 8), z + j - 32);
            }
            Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
            chunks.put(ForeignMap.key((x >> 4) - 1, z >> 4), new ForeignMap.Chunk(heights, water, "minecraft:plains", false));
            logic.setForeign(new ForeignMap(chunks, SETTINGS));
            int seam = (x >> 4) << 4;
            int mid = (z >> 4 << 4) + 8;
            RiverRegion.Hit there = logic.sampleRiver(seam, mid);
            double height = logic.sampleSurfaceHeight(seam, mid);
            for (int j = 0; j < plain.length; j++) {
                double level = logic.sampleWaterLevel(x + 8 * (j % 8), z + j - 32);
                assertTrue(Double.isNaN(level) || !Double.isNaN(plain[j]) && level <= plain[j] + 1.0E-6, "water risen with the land near " + x + "," + z);
            }
            logic.setForeign(ForeignMap.EMPTY);
            if (there != null && there.distance() > there.halfWidth(SETTINGS.rivers.minChannelWidth)) {
                assertEquals(ground, height, 12.0, "land by the seam at " + seam + "," + mid);
            }
        }
        assertTrue(tried > 0, "no river found");
    }

    /** A lake cut off by the edge of a map stays in: past the pool, the land stands above the water all the way around. */
    @Test
    void foreignLakesAreHeldIn() {
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        BlockColumn spot = logic.findSpawn();
        int chunkX = spot.x() >> 4;
        int chunkZ = spot.z() >> 4;
        short lake = 150;
        short[] heights = new short[ForeignMap.SAMPLES];
        short[] dry = new short[ForeignMap.SAMPLES];
        short[] wet = new short[ForeignMap.SAMPLES];
        Arrays.fill(heights, (short) 160);
        Arrays.fill(dry, ForeignMap.NO_WATER);
        Arrays.fill(wet, lake);
        Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
        for (int dz = -3; dz <= 3; dz++) {
            chunks.put(ForeignMap.key(chunkX, chunkZ + dz), new ForeignMap.Chunk(heights, dz == 0 ? wet : dry, "minecraft:plains", false));
        }
        logic.setForeign(new ForeignMap(chunks, SETTINGS));
        double reach = SETTINGS.foreign.pondReach;
        int edgeX = (chunkX + 1) * 16;
        int centerZ = chunkZ * 16 + 8;
        for (double angle = -1.5; angle <= 1.5; angle += 0.1) {
            int x = edgeX + (int) (1.1 * reach * Math.cos(angle));
            int z = centerZ + (int) (1.1 * reach * Math.sin(angle));
            if (x > edgeX + 1) {
                double water = logic.sampleWaterLevel(x, z);
                assertTrue(logic.sampleSurfaceHeight(x, z) >= lake || water == lake, "lake leaks at " + x + "," + z);
            }
        }
    }

    /** New terrain meets foreign chunks at their height, and leaves terrain far away as it was. */
    @Test
    void landClosesInOnForeignChunks() {
        TerrainLogic plain = SHARED;
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        BlockColumn spot = logic.findSpawn();
        int chunkX = spot.x() >> 4;
        int chunkZ = spot.z() >> 4;
        short ground = 180;
        short[] heights = new short[ForeignMap.SAMPLES];
        short[] water = new short[ForeignMap.SAMPLES];
        Arrays.fill(heights, ground);
        Arrays.fill(water, ForeignMap.NO_WATER);
        Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
        for (int dx = 0; dx < 8; dx++) {
            for (int dz = 0; dz < 8; dz++) {
                chunks.put(ForeignMap.key(chunkX + dx, chunkZ + dz), new ForeignMap.Chunk(heights, water, "minecraft:plains", false));
            }
        }
        logic.setForeign(new ForeignMap(chunks, SETTINGS));
        int edge = (chunkX + 8) * 16;
        int z = (chunkZ + 4) * 16;
        assertEquals(ground, logic.sampleSurfaceHeight(edge + 1, z), 6.0);
        double previous = logic.sampleSurfaceHeight(edge + 1, z);
        double steepest = 0.0;
        for (int x = edge + 9; x < edge + 400; x += 8) {
            double height = logic.sampleSurfaceHeight(x, z);
            steepest = Math.max(steepest, Math.abs(height - previous) / 8.0);
            previous = height;
        }
        assertTrue(steepest < 1.5, "blend grade " + steepest);
        int far = edge + (int) SETTINGS.foreign.maxWidth + 64;
        assertEquals(plain.sampleSurfaceHeight(far, z), logic.sampleSurfaceHeight(far, z));
    }

    /**
     * Chunks of an older MTM, made by a block of this world's own chunks, with their ground raised by a shift: what the
     * snapshot would hold if the algorithm had changed by that much there.
     */
    private static Map<Long, ForeignMap.Chunk> upgradedBlock(TerrainLogic plain, int chunkX, int chunkZ, int size, double shift) {
        Map<Long, ForeignMap.Chunk> chunks = new HashMap<>();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                short[] ground = new short[ForeignMap.SAMPLES];
                short[] water = new short[ForeignMap.SAMPLES];
                for (int j = 0; j < ForeignMap.SAMPLE_OFFSETS.length; j++) {
                    for (int i = 0; i < ForeignMap.SAMPLE_OFFSETS.length; i++) {
                        int x = (chunkX + dx) * 16 + ForeignMap.SAMPLE_OFFSETS[i];
                        int z = (chunkZ + dz) * 16 + ForeignMap.SAMPLE_OFFSETS[j];
                        int index = j * ForeignMap.SAMPLE_OFFSETS.length + i;
                        ground[index] = (short) Math.floor(plain.sampleSurfaceHeight(x, z) + shift);
                        double level = plain.sampleWaterLevel(x, z);
                        water[index] = Double.isNaN(level) ? ForeignMap.NO_WATER : (short) Math.floor(level + shift);
                    }
                }
                chunks.put(ForeignMap.key(chunkX + dx, chunkZ + dz), new ForeignMap.Chunk(ground, water, "minecraft:plains", true));
            }
        }
        return chunks;
    }

    /**
     * Where an upgrade has not changed the terrain, its old chunks leave the land around them as it is, rivers
     * included: they run on into the old chunks instead of ending in a lake before them.
     */
    @Test
    void unchangedUpgradedChunksLeaveTheLandAsItIs() {
        TerrainLogic plain = SHARED;
        Random random = new Random(11);
        int tried = 0;
        for (int i = 0; i < 20000 && tried < 4; i++) {
            int x = random.nextInt(16000) - 8000;
            int z = random.nextInt(16000) - 8000;
            RiverRegion.Hit river = plain.sampleRiver(x, z);
            if (river == null || river.distance() > 0.5 * river.halfWidth(SETTINGS.rivers.minChannelWidth)
                    || Double.isNaN(plain.sampleWaterLevel(x, z)) || plain.sampleSurfaceHeight(x, z) < SETTINGS.general.seaLevel) {
                continue;
            }
            tried++;
            TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
            int chunkX = (x >> 4) + 2;
            int chunkZ = (z >> 4) - 4;
            logic.setForeign(new ForeignMap(upgradedBlock(plain, chunkX, chunkZ, 8, 0.0), SETTINGS));
            for (int dz = -96; dz <= 96; dz += 4) {
                for (int dx = -160; dx < 0; dx += 4) {
                    int px = chunkX * 16 + dx;
                    int pz = z + dz;
                    assertEquals(plain.sampleSurfaceHeight(px, pz), logic.sampleSurfaceHeight(px, pz), 2.0, "land at " + px + "," + pz);
                    double before = plain.sampleWaterLevel(px, pz);
                    double after = logic.sampleWaterLevel(px, pz);
                    if (!Double.isNaN(before) && !Double.isNaN(after)) {
                        assertEquals(before, after, 2.0, "water at " + px + "," + pz);
                    }
                }
            }
            assertFalse(Double.isNaN(logic.sampleWaterLevel(x, z)), "river cut short at " + x + "," + z);
            assertEquals(plain.sampleWaterLevel(x, z), logic.sampleWaterLevel(x, z), 2.0, "river level at " + x + "," + z);
        }
        assertTrue(tried > 0, "no river found");
    }

    /**
     * Where an upgrade has changed the terrain, the land meets the old chunks at their height and keeps its own shape
     * on the way: the difference it makes up is spread smoothly across the blend, and land past it stays as it was.
     */
    @Test
    void landShiftsTowardChangedUpgradedChunks() {
        TerrainLogic plain = SHARED;
        TerrainLogic logic = new TerrainLogic(SEED, SETTINGS);
        BlockColumn spot = logic.findSpawn();
        int chunkX = spot.x() >> 4;
        int chunkZ = spot.z() >> 4;
        double shift = 30.0;
        logic.setForeign(new ForeignMap(upgradedBlock(plain, chunkX, chunkZ, 8, shift), SETTINGS));
        int edge = (chunkX + 8) * 16;
        int z = (chunkZ + 4) * 16;
        assertEquals(plain.sampleSurfaceHeight(edge + 1, z) + shift, logic.sampleSurfaceHeight(edge + 1, z), 4.0);
        double previous = shift;
        for (int x = edge + 9; x < edge + 400; x += 8) {
            double added = logic.sampleSurfaceHeight(x, z) - plain.sampleSurfaceHeight(x, z);
            assertTrue(added <= previous + 1.0, "shift grows again at " + x + "," + z);
            assertTrue(previous - added < 8.0 * 0.3, "shift drops too fast at " + x + "," + z);
            previous = added;
        }
        int far = edge + (int) SETTINGS.foreign.upgradeMaxWidth + 64;
        assertEquals(plain.sampleSurfaceHeight(far, z), logic.sampleSurfaceHeight(far, z));
    }

    @Test
    void sameSeedGivesSameSurface() {
        TerrainLogic a = SHARED;
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
        TerrainLogic logic = SHARED;
        WorldgenSettings.Surface surface = SETTINGS.surface;
        double sea = SETTINGS.general.seaLevel - 1;
        int rockCount = 0;
        for (int x = -8000; x < 8000; x += 37) {
            for (int z = -8000; z < 8000; z += 37) {
                double height = logic.sampleSurfaceHeight(x, z);
                if (logic.sampleSnow(x, z)) {
                    assertTrue(height >= sea, "snow under the sea at height " + height);
                }
                // Sea stacks are rock down to the water
                if (logic.sampleRock(x, z) && logic.sampleCliffShare(x, z) < 1.0) {
                    rockCount++;
                    assertTrue(height >= sea + surface.rockMinAboveSea, "rock at height " + height);
                }
            }
        }
        assertTrue(rockCount > 0, "mountains should expose rock");
    }

    @Test
    void oreStretchKeepsDensity() {
        double factor = SETTINGS.oreHeightScale();
        Random random = new Random(11);
        int vanillaTop = 100;
        int attempts = 400000;
        int[] heights = new int[(int) Math.ceil(vanillaTop * factor) + 2];
        for (int i = 0; i < attempts; i++) {
            int y = 1 + random.nextInt(vanillaTop);
            for (int stretched : OreHeights.stretch(y, factor, random::nextDouble)) {
                heights[stretched]++;
            }
        }
        // Ore per block of rock stays that of vanilla, all the way up the stretched range
        double vanillaDensity = (double) attempts / vanillaTop;
        int stretchedTop = (int) (vanillaTop * factor);
        for (int bin = 1; bin + 100 <= stretchedTop; bin += 100) {
            double sum = 0.0;
            for (int y = bin; y < bin + 100; y++) {
                sum += heights[y];
            }
            assertEquals(vanillaDensity, sum / 100.0, 0.05 * vanillaDensity, "density around height " + bin);
        }
        assertArrayEquals(new int[]{0}, OreHeights.stretch(0, factor, random::nextDouble), "heights up to 0 stay");
        assertArrayEquals(new int[]{-40}, OreHeights.stretch(-40, factor, random::nextDouble), "heights up to 0 stay");
    }

    @Test
    void tropicalAndColdZonesNeverTouch() {
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
        double sea = SETTINGS.general.seaLevel - 1;
        for (int x = -8000; x < 8000; x += 97) {
            for (int z = -8000; z < 8000; z += 97) {
                String biome = logic.sampleBiome(x, z);
                double height = logic.sampleSurfaceHeight(x, z);
                if (height < sea) {
                    // Deep river beds can lie below the sea, and they are rivers
                    assertTrue(biome.contains("ocean") || biome.contains("river"), biome + " under the sea at " + x + "," + z);
                } else {
                    assertTrue(!biome.contains("ocean"), biome + " on land at " + x + "," + z);
                }
            }
        }
    }

    /**
     * Dunes only ever add to the desert, gently, and fade out before its edge; this seed has a desert around -19000
     * 17500. Oases hold their water in banks, and their green land carries the oasis biome.
     */
    @Test
    void desertsGetDunesAndOases() {
        TerrainLogic logic = SHARED;
        WorldgenSettings flat = new WorldgenSettings();
        flat.deserts.dunes = false;
        TerrainLogic plain = new TerrainLogic(SEED, flat);
        Random random = new Random(17);
        double added = 0.0;
        int inside = 0;
        double steepest = 0.0;
        for (int i = 0; i < 20000; i++) {
            int x = -28000 + random.nextInt(2400);
            int z = 29600 + random.nextInt(2400);
            double dunes = logic.sampleSurfaceHeight(x, z) - plain.sampleSurfaceHeight(x, z);
            assertTrue(dunes > -1e-9, "dunes dig at " + x + "," + z);
            if (dunes > 0.0) {
                assertEquals(SETTINGS.biomes.desert, logic.sampleBiome(x, z), "dunes outside the desert at " + x + "," + z);
                double gx = logic.sampleSurfaceHeight(x + 1, z) - plain.sampleSurfaceHeight(x + 1, z) - dunes;
                double gz = logic.sampleSurfaceHeight(x, z + 1) - plain.sampleSurfaceHeight(x, z + 1) - dunes;
                steepest = Math.max(steepest, Math.hypot(gx, gz));
                inside++;
                added += dunes;
            }
        }
        assertTrue(inside > 1000 && added / inside > 1.0, "too few dunes: " + inside + " columns, " + added / Math.max(1, inside) + " high");
        // Half the slope limit of hills, with room for the wandering crests
        assertTrue(steepest < 0.6 * SETTINGS.slopes.hills / 100.0, "dunes as steep as " + steepest);

        int oases = 0;
        // Around the desert sampled above
        for (int x = -36000; x <= -24000; x += 48) {
            for (int z = 24000; z <= 36000; z += 48) {
                if (!logic.isOasis(x, z)) {
                    continue;
                }
                oases++;
                checkOasis(logic, x, z);
                z += (int) SETTINGS.deserts.oasisSpacing;
            }
        }
        assertTrue(oases > 0, "no oases");
    }

    /** Walks the green land around a column: it takes the oasis biome, and its pond's water never stands above a bank. */
    private static void checkOasis(TerrainLogic logic, int x0, int z0) {
        int reach = (int) (SETTINGS.deserts.pondRadiusMax * SETTINGS.deserts.greenRadius * (1.0 + SETTINGS.deserts.greenWarp)) * 2;
        int water = 0;
        for (int x = x0 - reach; x <= x0 + reach; x++) {
            for (int z = z0 - reach; z <= z0 + reach; z++) {
                if (!logic.isOasis(x, z)) {
                    continue;
                }
                double level = logic.sampleWaterLevel(x, z);
                if (Double.isNaN(level)) {
                    assertEquals(SETTINGS.biomes.oasis, logic.sampleBiome(x, z), "green land without its biome at " + x + "," + z);
                    continue;
                }
                water++;
                for (int d = 0; d < 4; d++) {
                    int nx = x + (d == 0 ? -1 : d == 1 ? 1 : 0);
                    int nz = z + (d == 2 ? -1 : d == 3 ? 1 : 0);
                    if (Double.isNaN(logic.sampleWaterLevel(nx, nz))) {
                        assertTrue(logic.sampleSurfaceHeight(nx, nz) >= level - 1e-6, "oasis water spills at " + nx + "," + nz);
                    }
                }
            }
        }
        assertTrue(water > 0, "oasis without water near " + x0 + "," + z0);
    }

    @Test
    void badlandsCanStayOffMountains() {
        WorldgenSettings settings = new WorldgenSettings();
        settings.biomes.unrealisticOnMountains = false;
        // Erosion only shapes the surface, and the biome rule does not depend on it
        settings.erosion.enabled = false;
        TerrainLogic logic = new TerrainLogic(12345L, settings);
        int mountains = 0;
        int badlands = 0;
        for (int x = -40000; x < 40000; x += 500) {
            for (int z = -40000; z < 40000; z += 500) {
                String biome = logic.sampleBiome(x, z);
                badlands += biome.contains("badlands") ? 1 : 0;
                if (logic.sampleLandformKind(x, z) == TerrainLogic.Landform.MOUNTAINS) {
                    mountains++;
                    assertTrue(!biome.contains("badlands"), biome + " on a mountain at " + x + "," + z);
                }
            }
        }
        assertTrue(mountains > 1000 && badlands > 0, mountains + " mountain samples, " + badlands + " badlands elsewhere");
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
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
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

    /**
     * Follows each river into the lake it feeds along its actual course, segment by segment, rather than straight
     * between routing nodes, which would miss the bends. From where the river first holds water to the lake, the
     * water must not be cut off by dry ground, nor climb on its way down. Regions on both sides of zero cover
     * negative coordinates and region borders.
     */
    @Test
    void riversEnterLakesContinuously() {
        TerrainLogic logic = SHARED;
        int regionSize = SETTINGS.general.regionSize;
        double approach = 96.0;
        int inlets = 0;
        int nearBorders = 0;
        int blocked = 0;
        int climbing = 0;
        List<String> worst = new ArrayList<>();
        for (int rx = -4; rx < 4; rx++) {
            for (int rz = -4; rz < 4; rz++) {
                RiverRegion rivers = logic.riverRegion(rx, rz);
                for (int s = 0; s < rivers.segments(); s++) {
                    double ex = rivers.endX(s);
                    double ez = rivers.endZ(s);
                    // Each segment once, by the region its downstream end lies in
                    if (Math.floorDiv((int) Math.floor(ex), regionSize) != rx || Math.floorDiv((int) Math.floor(ez), regionSize) != rz
                            || !inLake(logic, ex, ez) || inLake(logic, rivers.startX(s), rivers.startZ(s))) {
                        continue;
                    }
                    List<double[]> path = approachPath(rivers, s, approach);
                    double dryRun = 0.0;
                    double longestDry = 0.0;
                    double lowest = Double.NaN;
                    double climb = 0.0;
                    boolean wet = false;
                    for (double[] point : path) {
                        double water = logic.sampleWaterLevel(point[0], point[1]);
                        double ground = logic.sampleSurfaceHeight(point[0], point[1]);
                        boolean holds = !Double.isNaN(water) && ground < water;
                        if (holds) {
                            wet = true;
                            dryRun = 0.0;
                            climb = Math.max(climb, Double.isNaN(lowest) ? 0.0 : water - lowest);
                            lowest = Double.isNaN(lowest) ? water : Math.min(lowest, water);
                        } else if (wet) {
                            dryRun += point[2];
                            longestDry = Math.max(longestDry, dryRun);
                        }
                    }
                    if (!wet) {
                        // A source still too weak to hold water
                        continue;
                    }
                    inlets++;
                    double border = Math.min(Math.min(Math.floorMod((int) ex, regionSize), regionSize - Math.floorMod((int) ex, regionSize)),
                            Math.min(Math.floorMod((int) ez, regionSize), regionSize - Math.floorMod((int) ez, regionSize)));
                    nearBorders += border < approach ? 1 : 0;
                    boolean cut = longestDry > 2.0;
                    boolean rises = climb > 1.0;
                    blocked += cut ? 1 : 0;
                    climbing += rises ? 1 : 0;
                    if ((cut || rises) && worst.size() < 10) {
                        worst.add(String.format("%s %.0f %.0f: dry %.0f, climbs %.1f", rivers.riverClass(s), ex, ez, longestDry, climb));
                    }
                }
            }
        }
        System.out.println("lake inlets " + inlets + " near region borders " + nearBorders + " cut off " + blocked + " climbing " + climbing + " " + worst);
        assertTrue(inlets > 20, "too few lake inlets: " + inlets);
        assertTrue(blocked <= inlets / 50, blocked + " of " + inlets + " lake inlets are cut off by dry ground: " + worst);
        assertTrue(climbing <= inlets / 50, climbing + " of " + inlets + " lake inlets climb on the way in: " + worst);
    }

    private static boolean inLake(TerrainLogic logic, double x, double z) {
        double lake = logic.sampleLakeLevel(x, z);
        return !Double.isNaN(lake) && logic.sampleSurfaceHeight(x, z) < lake;
    }

    /**
     * Points every block along a river's course, from up to this far upstream of a segment down to its end, each with
     * the length of course it stands for.
     */
    private static List<double[]> approachPath(RiverRegion rivers, int segment, double approach) {
        List<Integer> chain = new ArrayList<>();
        double length = 0.0;
        for (int s = segment; s >= 0 && length < approach; s = rivers.upstream(s)) {
            chain.add(s);
            length += Math.hypot(rivers.endX(s) - rivers.startX(s), rivers.endZ(s) - rivers.startZ(s));
        }
        Collections.reverse(chain);
        List<double[]> path = new ArrayList<>();
        for (int s : chain) {
            double x0 = rivers.startX(s);
            double z0 = rivers.startZ(s);
            double span = Math.hypot(rivers.endX(s) - x0, rivers.endZ(s) - z0);
            int steps = Math.max(1, (int) Math.ceil(span));
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps;
                path.add(new double[] {x0 + t * (rivers.endX(s) - x0), z0 + t * (rivers.endZ(s) - z0), span / steps});
            }
        }
        return path;
    }

    /**
     * Land is designed for designHeight, and only ranges on plateaus rise above it toward the surface maximum: away from
     * them and their feet a world is the same as one whose maximum is the design height. Settings written before designHeight existed
     * design their land for their maximum, as they did then.
     */
    @Test
    void onlyRangesOnPlateausRiseAboveTheDesignHeight() {
        WorldgenSettings written = WorldgenSettings.fromJson("{\"general\": {\"maxSurfaceHeight\": 1024}}");
        assertEquals(1024, written.general.designHeight);
        assertEquals(750, WorldgenSettings.fromJson("{\"general\": {\"caves\": false}}").general.designHeight);
        TerrainLogic designed = new TerrainLogic(SEED, WorldgenSettings.fromJson("{\"general\": {\"maxSurfaceHeight\": "
                + SETTINGS.general.designHeight + "}}"));
        int land = 0;
        int same = 0;
        int high = 0;
        for (int x = -60000; x < 60000; x += 600) {
            for (int z = -60000; z < 60000; z += 600) {
                double h = SHARED.sampleOverviewHeight(x, z);
                if (h < SETTINGS.general.seaLevel) {
                    continue;
                }
                land++;
                double design = designed.sampleOverviewHeight(x, z);
                same += h == design ? 1 : 0;
                high += h > 1300 ? 1 : 0;
                assertTrue(h < SETTINGS.general.maxSurfaceHeight, "land above the maximum at " + x + "," + z);
            }
        }
        // Plateaus hold about a fifth of the land; ranges on them grow wider as they grow taller, and their feet reach a
        // little past the plateaus
        assertTrue(same > 0.7 * land, same + " of " + land + " samples as designed");
        assertTrue(high > 0, "no range rises above 1300");
    }

    /**
     * Rivers keep to rivers.plateauMaxWidth wherever the land counts as plateau, up to the flare where a branch meets
     * the river it leaves. Settings written before the limit existed keep them as wide as anywhere.
     */
    @Test
    void riversOnPlateausStayNarrow() {
        assertEquals(0.0, WorldgenSettings.fromJson(SETTINGS.toJson().replace("\"plateauMaxWidth\"", "\"unknown\"")).rivers.plateauMaxWidth);
        double limit = SETTINGS.rivers.plateauMaxWidth * SETTINGS.rivers.mouthFlare + 1e-6;
        int onPlateau = 0;
        for (int x = -8000; x < 8000; x += 16) {
            for (int z = -8000; z < 8000; z += 256) {
                RiverRegion.Hit river = SHARED.sampleRiver(x, z);
                if (river == null || river.distance() > 0.5 * river.width() || SHARED.plateauShare(x, z) < 0.5) {
                    continue;
                }
                onPlateau++;
                assertTrue(river.width() <= limit, "river " + river.width() + " wide on a plateau at " + x + "," + z);
            }
        }
        assertTrue(onPlateau > 50, "only " + onPlateau + " river samples on plateaus");
    }

    /**
     * Past the shelf along the coasts the open ocean deepens: a good part of it lies more than 50 blocks deep, never
     * down to the bottom of the world. Settings written before keep the shallow ocean they had.
     */
    @Test
    void openOceansAreDeep() {
        assertEquals(0.0, WorldgenSettings.fromJson(SETTINGS.toJson().replace("\"abyssDepth\"", "\"unknown\"")).continents.abyssDepth);
        double sea = SETTINGS.general.seaLevel - 1;
        int ocean = 0;
        int deep = 0;
        for (int x = -60000; x < 60000; x += 600) {
            for (int z = -60000; z < 60000; z += 600) {
                double floor = SHARED.sampleOverviewHeight(x, z);
                if (floor >= sea) {
                    continue;
                }
                ocean++;
                deep += sea - floor > 50.0 ? 1 : 0;
                assertTrue(floor > SETTINGS.minBuildY() + 8, "sea floor at " + floor + " at " + x + "," + z);
            }
        }
        assertTrue(deep > 0.3 * ocean, deep + " of " + ocean + " ocean samples deeper than 50 blocks");
    }

    /** /mwg locate finds each kind of land within its radius, and what it finds is that kind of land. */
    @Test
    void locateFindsEachKindOfLand() {
        TerrainLogic logic = SHARED;
        for (TerrainLogic.Feature feature : TerrainLogic.Feature.values()) {
            BlockColumn found = logic.findNearest(feature, 0, 0, 64, 16384);
            assertTrue(found != null, "no " + feature + " found");
            int x = found.x();
            int z = found.z();
            switch (feature) {
                case MOUNTAIN -> assertTrue(logic.sampleOverviewHeight(x, z) >= SETTINGS.general.designHeight / 2.0);
                case PLAINS -> assertEquals(TerrainLogic.Landform.PLAINS, logic.sampleLandformKind(x, z));
                case PLATEAU -> assertEquals(TerrainLogic.Landform.PLATEAU, logic.sampleLandformKind(x, z));
                case OCEAN -> assertTrue(logic.sampleLandformKind(x, z) == TerrainLogic.Landform.OCEAN && logic.sampleCoastDistance(x, z) < 0.0);
                case LAKE -> assertTrue(!Double.isNaN(logic.sampleLakeLevel(x, z)), "lake without water at " + x + "," + z);
            }
        }
    }

    /** With plate tectonics off the plate settings change nothing, and the layout differs from the one plates give. */
    @Test
    void plateTectonicsCanBeSwitchedOff() {
        WorldgenSettings off = WorldgenSettings.fromJson(SETTINGS.toJson());
        off.mountains.plateTectonics = false;
        WorldgenSettings offTuned = WorldgenSettings.fromJson(off.toJson());
        offTuned.mountains.beltStrength = 0.1;
        offTuned.mountains.plateRelief = 0.0;
        offTuned.mountains.zoneNoise = 2.0;
        offTuned.plateaus.plateChance = 0.9;
        offTuned.plateaus.beltShare = 3.0;
        TerrainLogic a = new TerrainLogic(SEED, off);
        TerrainLogic b = new TerrainLogic(SEED, offTuned);
        TerrainLogic plates = new TerrainLogic(SEED, SETTINGS);
        Random random = new Random(23);
        int differ = 0;
        for (int i = 0; i < 3000; i++) {
            double x = random.nextInt(120000) - 60000;
            double z = random.nextInt(120000) - 60000;
            assertEquals(a.sampleSmoothLand(x, z), b.sampleSmoothLand(x, z), "plate settings changed the land at " + x + "," + z);
            assertEquals(a.sampleLandformKind(x, z), b.sampleLandformKind(x, z));
            differ += a.sampleLandformKind(x, z) != plates.sampleLandformKind(x, z) ? 1 : 0;
        }
        assertTrue(differ > 300, "switching plates off changed only " + differ + " of 3000 samples");
    }

    /** Where a river's route ends in the sea, the real surface there must be sea, with nothing closing off the mouth. */
    @Test
    void riversEndInOpenSea() {
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
        Random random = new Random(13);
        List<Double> rises = new ArrayList<>();
        // River queries erode every region they reach, so the area stays within what the region cache holds
        for (int i = 0; i < 400000 && rises.size() < 1000; i++) {
            int x = random.nextInt(16000) - 8000;
            int z = random.nextInt(16000) - 8000;
            RiverRegion.Hit river = logic.sampleRiver(x, z);
            // Ground near the coast stays low by design, so that nothing closes off river mouths. Mountains have no
            // floodplains: their rivers run in the valleys erosion cut
            if (river == null || logic.sampleLandformKind(x, z) == TerrainLogic.Landform.MOUNTAINS || river.level() < SETTINGS.general.seaLevel + SETTINGS.rivers.coastBlend + 4.0
                    // Run and width can belong to different rivers: the bank of a stream beside a big river far off
                    // is hill land, not the big river's plain
                    || river.width() < 100.0 || river.run() <= 0.0
                    || river.distance() - river.halfWidth(SETTINGS.rivers.minChannelWidth) > 150.0
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
        TerrainLogic logic = SHARED;
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
    void coastsCoverAllTypes() {
        TerrainLogic logic = SHARED;
        java.util.EnumMap<CoastLayer.Type, Integer> counts = new java.util.EnumMap<>(CoastLayer.Type.class);
        Random random = new Random(21);
        for (int i = 0; i < 20000; i++) {
            counts.merge(logic.sampleCoastType(random.nextInt(120000) - 60000, random.nextInt(120000) - 60000), 1, Integer::sum);
        }
        for (CoastLayer.Type type : CoastLayer.Type.values()) {
            assertTrue(counts.getOrDefault(type, 0) > 500, type + " is too rare: " + counts);
        }
    }

    /** Islands are scattered sparsely over the sea, away from land. */
    @Test
    void islandsDotTheOcean() {
        TerrainLogic logic = SHARED;
        double sea = SETTINGS.general.seaLevel - 1;
        int ocean = 0;
        int island = 0;
        for (int x = -100000; x < 100000; x += 64) {
            for (int z = -100000; z < 100000; z += 1024) {
                double height = logic.sampleContinentHeight(x, z);
                if (height < sea) {
                    ocean++;
                } else if (logic.isIsland(x, z)) {
                    island++;
                }
            }
        }
        double share = (double) island / (ocean + island);
        assertTrue(share > 0.001 && share < 0.05, "islands cover " + share + " of the sea");
    }

    @Test
    void waterNeverRunsUphill() {
        TerrainLogic logic = SHARED;
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
        TerrainLogic logic = SHARED;
        FlowRegion flow = logic.flowRegion(0, 0);
        int cells = flow.cells();
        int size = SETTINGS.general.regionSize / cells;
        double sea = SETTINGS.general.seaLevel - 1;
        for (int lz = 0; lz < cells; lz++) {
            for (int lx = 0; lx < cells; lx++) {
                double height = logic.sampleSurfaceHeight((lx + 0.5) * size, (lz + 0.5) * size);
                // Routing samples coarsely, so cells right at the coast can count as sea
                assertTrue(flow.flows(lx, lz) || flow.isSink(lx, lz) || height < sea + 16.0 + SETTINGS.heightScale() * SETTINGS.beaches.cliffHeight, "land sink at height " + height);
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
    void surfacesUpToTheBuildLimitAreAccepted() {
        // As high as JJThunder's worlds go: the build top stops at vanilla's limit instead of rejecting the setting
        WorldgenSettings tall = WorldgenSettings.fromJson("{\"general\": {\"maxSurfaceHeight\": 2032}}");
        assertEquals(2032, tall.minBuildY() + tall.buildHeight());
        assertTrue(WorldgenSettings.possibleBuildHeights().contains(tall.buildHeight()), "no dimension type for the build height");
        assertThrows(IllegalArgumentException.class,
                () -> WorldgenSettings.fromJson("{\"general\": {\"maxSurfaceHeight\": 2033}}"));
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
        TerrainLogic logic = SHARED;
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
