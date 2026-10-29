package com.mtm.worldgen.gametest;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * In-game checks of the full MTM generation path: seed binding mixin, density functions, aquifer and biome
 * source. The game test server always runs a flat world, so each test builds its own MTM generator and samples
 * it with NoiseBasedChunkGenerator#getBaseColumn, which computes block columns without generating chunks.
 *
 * <p>Shared by both loaders; the expected column hash is the same constant on Forge and Fabric, which is how
 * the tests prove both loaders generate identical terrain.
 */
public final class MtmGameTests {
    /**
     * Empty 1x1x1 test structure. Loaders resolve the name differently, so both files exist under
     * data/minecraft: Fabric loads gametest/structures/mtm_worldgen_empty.snbt, Forge prefixes the class name
     * and loads structures/mtmgametests.mtm_worldgen_empty.nbt.
     */
    private static final String TEMPLATE = "mtm_worldgen_empty";
    private static final int TIMEOUT_TICKS = 400;

    private static final long SEED = 20261029L;
    private static final int SAMPLE_ORIGIN = -6000;
    private static final int SAMPLE_STEP = 397;
    private static final int SAMPLES_PER_AXIS = 32;
    /**
     * Hash of all sampled columns for {@link #SEED}. Changes whenever terrain generation changes on purpose:
     * update it then, together with LogicRegion.ALGORITHM_VERSION if saved worlds are affected.
     */
    private static final long EXPECTED_COLUMN_HASH = 0x7ffb213186bca57eL;

    private static final int SEA_SURFACE_Y = 62;
    private static final double DEEP_OCEAN_COAST_DISTANCE = -1200.0;
    private static final double FAR_INLAND_COAST_DISTANCE = 1200.0;

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsMatchExpectedHash(GameTestHelper helper) {
        TerrainLogic.forget(SEED);
        long actual = hashColumns(generator(helper), helper.getLevel(), samplePoints());
        if (actual != EXPECTED_COLUMN_HASH) {
            helper.fail("Column hash 0x" + Long.toHexString(actual) + " != expected 0x"
                    + Long.toHexString(EXPECTED_COLUMN_HASH) + " (terrain changed, or loaders disagree)");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsDoNotDependOnSamplingOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED);
        List<Long> forward = columnHashes(generator(helper), level, points);

        TerrainLogic.forget(SEED);
        List<int[]> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        List<Long> backward = columnHashes(generator(helper), level, reversed);
        Collections.reverse(backward);

        if (!forward.equals(backward)) {
            helper.fail("Columns differ when sampled in reverse order with cold caches");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsDoNotDependOnThreads(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED);
        List<Long> sequential = columnHashes(generator(helper), level, points);

        TerrainLogic.forget(SEED);
        ChunkGenerator shared = generator(helper);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int[] p : points) {
                futures.add(pool.submit(() -> hashColumn(shared.getBaseColumn(p[0], p[1], level), level)));
            }
            for (int i = 0; i < points.size(); i++) {
                if (!futures.get(i).get().equals(sequential.get(i))) {
                    helper.fail("Column at " + points.get(i)[0] + "," + points.get(i)[1] + " differs when computed concurrently");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void surfaceIsContinuousAcrossRegionBorders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = generator(helper);
        int border = TerrainLogic.forSeed(SEED).settings().regionSize();
        for (int z = -border; z <= border; z += 61) {
            int west = generator.getBaseHeight(border - 1, z, Heightmap.Types.OCEAN_FLOOR_WG, level);
            int east = generator.getBaseHeight(border, z, Heightmap.Types.OCEAN_FLOOR_WG, level);
            if (Math.abs(east - west) > 1) {
                helper.fail("Surface jumps from " + west + " to " + east + " across the region border at z=" + z);
            }
        }
        helper.succeed();
    }

    /** Regression check for dry land below sea level next to walls of sea water. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void landBelowSeaLevelIsFlooded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = generator(helper);
        for (int[] p : samplePoints()) {
            NoiseColumn column = generator.getBaseColumn(p[0], p[1], level);
            for (int y = SEA_SURFACE_Y; y >= level.getMinBuildHeight(); y--) {
                BlockState state = column.getBlock(y);
                if (!state.isAir()) {
                    break;
                }
                helper.fail("Dry air at " + p[0] + "," + y + "," + p[1] + " below sea level");
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void biomesFollowTerrain(GameTestHelper helper) {
        ChunkGenerator generator = generator(helper);
        TerrainLogic logic = TerrainLogic.forSeed(SEED);
        int checkedOcean = 0;
        int checkedInland = 0;
        for (int[] p : samplePoints()) {
            double coast = logic.coastDistance(p[0], p[1]);
            int surfaceY = (int) Math.floor(logic.surfaceHeight(p[0], p[1]));
            Holder<Biome> biome = generator.getNoiseBiome(QuartPos.fromBlock(p[0]),
                    QuartPos.fromBlock(Math.max(surfaceY, SEA_SURFACE_Y)), QuartPos.fromBlock(p[1]));
            if (coast < DEEP_OCEAN_COAST_DISTANCE) {
                checkedOcean++;
                if (!biome.is(BiomeTags.IS_OCEAN)) {
                    helper.fail("Non-ocean biome " + name(biome) + " in open ocean at " + p[0] + "," + p[1]);
                }
            } else if (coast > FAR_INLAND_COAST_DISTANCE) {
                checkedInland++;
                if (biome.is(BiomeTags.IS_OCEAN) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.LUSH_CAVES)) {
                    helper.fail("Biome " + name(biome) + " on inland surface at " + p[0] + "," + surfaceY + "," + p[1]);
                }
            }
        }
        if (checkedOcean == 0 || checkedInland == 0) {
            helper.fail("Sample area has no open ocean or no far inland points; move the sample area");
        }
        helper.succeed();
    }

    private static ChunkGenerator generator(GameTestHelper helper) {
        return MtmGenerators.create(helper.getLevel().registryAccess(), SEED);
    }

    private static List<int[]> samplePoints() {
        List<int[]> points = new ArrayList<>();
        for (int i = 0; i < SAMPLES_PER_AXIS; i++) {
            for (int j = 0; j < SAMPLES_PER_AXIS; j++) {
                points.add(new int[]{SAMPLE_ORIGIN + i * SAMPLE_STEP, SAMPLE_ORIGIN + j * SAMPLE_STEP});
            }
        }
        return points;
    }

    private static long hashColumns(ChunkGenerator generator, ServerLevel level, List<int[]> points) {
        long hash = 0x9E3779B97F4A7C15L;
        for (long column : columnHashes(generator, level, points)) {
            hash = hash * 31 + column;
        }
        return hash;
    }

    private static List<Long> columnHashes(ChunkGenerator generator, ServerLevel level, List<int[]> points) {
        List<Long> hashes = new ArrayList<>(points.size());
        for (int[] p : points) {
            hashes.add(hashColumn(generator.getBaseColumn(p[0], p[1], level), level));
        }
        return hashes;
    }

    /** Hashes block registry names, not numeric state ids, which may differ between loaders. */
    private static long hashColumn(NoiseColumn column, ServerLevel level) {
        long hash = 17L;
        for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
            BlockState state = column.getBlock(y);
            String block = state.is(Blocks.AIR) ? "air" : Registry.BLOCK.getKey(state.getBlock()).toString();
            hash = hash * 1_000_003L + block.hashCode();
        }
        return hash;
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("?");
    }
}
