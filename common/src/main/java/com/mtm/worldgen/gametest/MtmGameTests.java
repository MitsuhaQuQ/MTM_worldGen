package com.mtm.worldgen.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.benchmark.GenerationBenchmark;
import com.mtm.worldgen.logic.BlockColumn;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.Blender;

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
    private static final long EXPECTED_COLUMN_HASH = 0xc95fcb0fea348ab2L;

    private static final int SEA_SURFACE_Y = 62;
    private static final double DEEP_OCEAN_COAST_DISTANCE = -1200.0;
    private static final double FAR_INLAND_COAST_DISTANCE = 1200.0;
    /** One block apart, mountains up to 150% grade can differ by 2 blocks after rounding; more is a seam. */
    private static final int MAX_BORDER_STEP = 2;

    /** MTM build range. The game test level is a vanilla-height flat world, so columns are sampled with this. */
    private static final LevelHeightAccessor MTM_HEIGHT = new LevelHeightAccessor() {
        @Override
        public int getHeight() {
            return MtmGenerators.HEIGHT;
        }

        @Override
        public int getMinBuildHeight() {
            return MtmGenerators.MIN_Y;
        }
    };

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsMatchExpectedHash(GameTestHelper helper) {
        TerrainLogic.forget(SEED);
        long actual = hashColumns(generator(helper), samplePoints());
        if (actual != EXPECTED_COLUMN_HASH) {
            helper.fail("Column hash 0x" + Long.toHexString(actual) + " != expected 0x"
                    + Long.toHexString(EXPECTED_COLUMN_HASH) + " (terrain changed, or loaders disagree)");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsDoNotDependOnSamplingOrder(GameTestHelper helper) {
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED);
        List<Long> forward = columnHashes(generator(helper), points);

        TerrainLogic.forget(SEED);
        List<int[]> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        List<Long> backward = columnHashes(generator(helper), reversed);
        Collections.reverse(backward);

        if (!forward.equals(backward)) {
            helper.fail("Columns differ when sampled in reverse order with cold caches");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsDoNotDependOnThreads(GameTestHelper helper) throws Exception {
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED);
        List<Long> sequential = columnHashes(generator(helper), points);

        TerrainLogic.forget(SEED);
        ChunkGenerator shared = generator(helper);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int[] p : points) {
                futures.add(pool.submit(() -> hashColumn(shared.getBaseColumn(p[0], p[1], MTM_HEIGHT))));
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
        ChunkGenerator generator = generator(helper);
        int border = TerrainLogic.forSeed(SEED).settings().regionSize();
        for (int z = -border; z <= border; z += 61) {
            int west = generator.getBaseHeight(border - 1, z, Heightmap.Types.OCEAN_FLOOR_WG, MTM_HEIGHT);
            int east = generator.getBaseHeight(border, z, Heightmap.Types.OCEAN_FLOOR_WG, MTM_HEIGHT);
            if (Math.abs(east - west) > MAX_BORDER_STEP) {
                helper.fail("Surface jumps from " + west + " to " + east + " across the region border at z=" + z);
            }
        }
        helper.succeed();
    }

    /** Regression check for dry land below sea level next to walls of sea water. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void landBelowSeaLevelIsFlooded(GameTestHelper helper) {
        ChunkGenerator generator = generator(helper);
        for (int[] p : samplePoints()) {
            NoiseColumn column = generator.getBaseColumn(p[0], p[1], MTM_HEIGHT);
            for (int y = SEA_SURFACE_Y; y >= MTM_HEIGHT.getMinBuildHeight(); y--) {
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

    /**
     * Worlds are created and loaded through the generator codec, which wraps density functions in holders.
     * Spawn placement and the locate command must still recognize the decoded generator as MTM.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void generatorIsRecognizedAfterSaveAndLoad(GameTestHelper helper) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        JsonElement saved = ChunkGenerator.CODEC.encodeStart(ops, generator(helper))
                .getOrThrow(false, message -> helper.fail("Encoding failed: " + message));
        ChunkGenerator loaded = ChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(false, message -> helper.fail("Decoding failed: " + message));
        if (MtmGenerators.terrainLogic(loaded) == null) {
            helper.fail("Decoded generator is not recognized as an MTM generator");
        }
        helper.succeed();
    }

    /**
     * Chunk filling stops above the surface as an optimization, a path the column based tests do not cover. Fills
     * real chunks on flat land and on a mountain and compares them with full height columns, block by block.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void filledChunksMatchColumns(GameTestHelper helper) {
        ChunkGenerator generator = generator(helper);
        TerrainLogic logic = TerrainLogic.forSeed(SEED);
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registry.BIOME_REGISTRY);
        StructureFeatureManager structures = GenerationBenchmark.noStructures(helper.getLevel());

        BlockColumn mountain = logic.findNearestAbove(logic.settings().maxSurfaceHeight() / 2.0, 0, 0, 64, 16384);
        if (mountain == null) {
            helper.fail("No mountain found near the origin to test against");
        }
        for (ChunkPos pos : List.of(new ChunkPos(0, 0), new ChunkPos(new BlockPos(mountain.x(), 0, mountain.z())))) {
            ChunkAccess chunk = new ProtoChunk(pos, UpgradeData.EMPTY, MTM_HEIGHT, biomes, null);
            generator.fillFromNoise(Runnable::run, Blender.empty(), structures, chunk).join();
            for (int[] offset : new int[][]{{0, 0}, {7, 7}, {15, 15}, {3, 12}}) {
                int x = pos.getMinBlockX() + offset[0];
                int z = pos.getMinBlockZ() + offset[1];
                NoiseColumn column = generator.getBaseColumn(x, z, MTM_HEIGHT);
                for (int y = MTM_HEIGHT.getMinBuildHeight(); y < MTM_HEIGHT.getMaxBuildHeight(); y++) {
                    BlockState filled = chunk.getBlockState(new BlockPos(x, y, z));
                    if (filled.getBlock() != column.getBlock(y).getBlock()) {
                        helper.fail("Chunk has " + filled + " but the column has " + column.getBlock(y)
                                + " at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        helper.succeed();
    }

    /** Logs generation speed compared with vanilla. Timing varies by machine, so it reports and never fails. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS, required = false)
    public void reportGenerationBenchmark(GameTestHelper helper) {
        MtmWorldgen.LOGGER.info("Benchmark: {}", GenerationBenchmark.run(helper.getLevel()).summary());
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

    private static long hashColumns(ChunkGenerator generator, List<int[]> points) {
        long hash = 0x9E3779B97F4A7C15L;
        for (long column : columnHashes(generator, points)) {
            hash = hash * 31 + column;
        }
        return hash;
    }

    private static List<Long> columnHashes(ChunkGenerator generator, List<int[]> points) {
        List<Long> hashes = new ArrayList<>(points.size());
        for (int[] p : points) {
            hashes.add(hashColumn(generator.getBaseColumn(p[0], p[1], MTM_HEIGHT)));
        }
        return hashes;
    }

    /** Hashes block registry names, not numeric state ids, which may differ between loaders. */
    private static long hashColumn(NoiseColumn column) {
        long hash = 17L;
        for (int y = MTM_HEIGHT.getMinBuildHeight(); y < MTM_HEIGHT.getMaxBuildHeight(); y++) {
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
