package com.mtm.worldgen.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.benchmark.GenerationBenchmark;
import com.mtm.worldgen.logic.BlockColumn;
import com.mtm.worldgen.logic.ForeignMap;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.world.ForeignChunks;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmOres;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.placement.OrePlacements;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    /** Fixed defaults, so a local config file cannot change what the tests and the golden hash see. */
    private static final WorldgenSettings SETTINGS = WorldgenSettings.DEFAULT;
    private static final int SAMPLE_ORIGIN = -6000;
    private static final int SAMPLE_STEP = 397;
    private static final int SAMPLES_PER_AXIS = 32;
    /**
     * Hash of all sampled columns for {@link #SEED}. Changes whenever terrain generation changes on purpose:
     * update it then, together with LogicRegion.ALGORITHM_VERSION if saved worlds are affected.
     */
    private static final long EXPECTED_COLUMN_HASH = 0x10a5890ea4b610c7L;

    private static final int SEA_SURFACE_Y = 62;
    private static final double DEEP_OCEAN_COAST_DISTANCE = -1200.0;
    private static final double FAR_INLAND_COAST_DISTANCE = 1200.0;
    /** One block apart, mountains up to 150% grade can differ by 2 blocks after rounding; more is a seam. */
    private static final int MAX_BORDER_STEP = 2;

    /** MTM build range. The game test level is a vanilla-height flat world, so columns are sampled with this. */
    /** A generator with the seed bound to it, which vanilla keeps apart. */
    private record World(ChunkGenerator generator, RandomState random) {
        NoiseColumn column(int x, int z) {
            return generator.getBaseColumn(x, z, MTM_HEIGHT, random);
        }

        int height(int x, int z) {
            return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, MTM_HEIGHT, random);
        }
    }

    private static final LevelHeightAccessor MTM_HEIGHT = new LevelHeightAccessor() {
        @Override
        public int getHeight() {
            return SETTINGS.buildHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return SETTINGS.minBuildY();
        }
    };

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsMatchExpectedHash(GameTestHelper helper) {
        TerrainLogic.forget(SEED, SETTINGS);
        long actual = hashColumns(world(helper), samplePoints());
        if (actual != EXPECTED_COLUMN_HASH) {
            helper.fail("Column hash 0x" + Long.toHexString(actual) + " != expected 0x"
                    + Long.toHexString(EXPECTED_COLUMN_HASH) + " (terrain changed, or loaders disagree)");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsDoNotDependOnSamplingOrder(GameTestHelper helper) {
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED, SETTINGS);
        List<Long> forward = columnHashes(world(helper), points);

        TerrainLogic.forget(SEED, SETTINGS);
        List<int[]> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        List<Long> backward = columnHashes(world(helper), reversed);
        Collections.reverse(backward);

        if (!forward.equals(backward)) {
            helper.fail("Columns differ when sampled in reverse order with cold caches");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void columnsDoNotDependOnThreads(GameTestHelper helper) throws Exception {
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED, SETTINGS);
        List<Long> sequential = columnHashes(world(helper), points);

        TerrainLogic.forget(SEED, SETTINGS);
        World shared = world(helper);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int[] p : points) {
                futures.add(pool.submit(() -> hashColumn(shared.column(p[0], p[1]))));
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
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        int border = SETTINGS.general.regionSize;
        for (int z = -border; z <= border; z += 61) {
            // Cliffs and sea stacks are steeper than any slope limit by design
            if (logic.sampleCliffShare(border, z) > 0.0 || logic.sampleCliffShare(border - 1, z) > 0.0) {
                continue;
            }
            int west = world.height(border - 1, z);
            int east = world.height(border, z);
            if (Math.abs(east - west) > MAX_BORDER_STEP) {
                helper.fail("Surface jumps from " + west + " to " + east + " across the region border at z=" + z);
            }
        }
        helper.succeed();
    }

    /** Regression check for dry land below sea level next to walls of sea water. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void landBelowSeaLevelIsFlooded(GameTestHelper helper) {
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        for (int[] p : samplePoints()) {
            // Under land, air at this height is a cave
            if (logic.surfaceHeight(p[0], p[1]) >= SEA_SURFACE_Y) {
                continue;
            }
            NoiseColumn column = world.column(p[0], p[1]);
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
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        int checkedOcean = 0;
        int checkedInland = 0;
        for (int[] p : samplePoints()) {
            double coast = logic.coastDistance(p[0], p[1]);
            int surfaceY = (int) Math.floor(logic.surfaceHeight(p[0], p[1]));
            Holder<Biome> biome = world.generator().getBiomeSource().getNoiseBiome(QuartPos.fromBlock(p[0]),
                    QuartPos.fromBlock(Math.max(surfaceY, SEA_SURFACE_Y)), QuartPos.fromBlock(p[1]), world.random().sampler());
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
        JsonElement saved = ChunkGenerator.CODEC.encodeStart(ops, world(helper).generator())
                .getOrThrow(false, message -> helper.fail("Encoding failed: " + message));
        ChunkGenerator loaded = ChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(false, message -> helper.fail("Decoding failed: " + message));
        if (MtmGenerators.terrainLogic(MtmGenerators.randomState(helper.getLevel().registryAccess(), loaded, SEED)) == null) {
            helper.fail("Decoded generator is not recognized as an MTM generator");
        }
        helper.succeed();
    }

    /** The world preset is a placeholder until the config applies; both world creation paths go through this. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void worldPresetBuildsMtmWorld(GameTestHelper helper) {
        RegistryAccess access = helper.getLevel().registryAccess();
        WorldDimensions dimensions = access.registryOrThrow(Registries.WORLD_PRESET)
                .getOrThrow(ResourceKey.create(Registries.WORLD_PRESET, new ResourceLocation(MtmWorldgen.MOD_ID, "realistic")))
                .createWorldDimensions();
        ChunkGenerator generator = dimensions.overworld();
        if (MtmGenerators.terrainLogic(MtmGenerators.randomState(access, generator, SEED)) == null) {
            helper.fail("The world preset did not build an MTM generator");
        }
        DimensionType type = dimensions.get(LevelStem.OVERWORLD).orElseThrow().type().value();
        NoiseSettings noise = ((NoiseBasedChunkGenerator) generator).generatorSettings().value().noiseSettings();
        if (type.minY() != noise.minY() || type.height() != noise.height()) {
            helper.fail("Dimension type height " + type.height() + " does not match generator height " + noise.height());
        }
        helper.succeed();
    }

    /**
     * Chunk filling stops above the surface as an optimization, a path the column based tests do not cover. Fills
     * real chunks on flat land and on a mountain and compares them with full height columns, block by block.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void filledChunksMatchColumns(GameTestHelper helper) {
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        StructureManager structures = GenerationBenchmark.noStructures(helper.getLevel());

        BlockColumn mountain = logic.findNearestAbove(SETTINGS.general.maxSurfaceHeight / 2.0, 0, 0, 64, 16384);
        if (mountain == null) {
            helper.fail("No mountain found near the origin to test against");
        }
        for (ChunkPos pos : List.of(new ChunkPos(0, 0), new ChunkPos(new BlockPos(mountain.x(), 0, mountain.z())))) {
            ChunkAccess chunk = new ProtoChunk(pos, UpgradeData.EMPTY, MTM_HEIGHT, biomes, null);
            world.generator().fillFromNoise(Runnable::run, Blender.empty(), world.random(), structures, chunk).join();
            for (int[] offset : new int[][]{{0, 0}, {7, 7}, {15, 15}, {3, 12}}) {
                int x = pos.getMinBlockX() + offset[0];
                int z = pos.getMinBlockZ() + offset[1];
                NoiseColumn column = world.column(x, z);
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

    private static World world(GameTestHelper helper) {
        return world(helper, SETTINGS);
    }

    private static World world(GameTestHelper helper, WorldgenSettings settings) {
        RegistryAccess access = helper.getLevel().registryAccess();
        ChunkGenerator generator = MtmGenerators.create(access,
                access.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD), settings);
        return new World(generator, MtmGenerators.randomState(access, generator, SEED));
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

    private static long hashColumns(World world, List<int[]> points) {
        long hash = 0x9E3779B97F4A7C15L;
        for (long column : columnHashes(world, points)) {
            hash = hash * 31 + column;
        }
        return hash;
    }

    private static List<Long> columnHashes(World world, List<int[]> points) {
        List<Long> hashes = new ArrayList<>(points.size());
        for (int[] p : points) {
            hashes.add(hashColumn(world.column(p[0], p[1])));
        }
        return hashes;
    }

    /** Hashes block registry names, not numeric state ids, which may differ between loaders. */
    private static long hashColumn(NoiseColumn column) {
        long hash = 17L;
        for (int y = MTM_HEIGHT.getMinBuildHeight(); y < MTM_HEIGHT.getMaxBuildHeight(); y++) {
            BlockState state = column.getBlock(y);
            String block = state.is(Blocks.AIR) ? "air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            hash = hash * 1_000_003L + block.hashCode();
        }
        return hash;
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("?");
    }

    /**
     * Runs a real superflat chunk of the test world through the fitting of foreign chunks: it has to come back raised
     * to the target ground, with no vanilla bedrock left in it and rock generated below, and only once.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public static void foreignFlatChunkIsFitted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = SEED + 1;
        TerrainLogic logic = TerrainLogic.forWorld(seed, SETTINGS);
        ChunkPos pos = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        Path root;
        try {
            root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            try (ChunkStorage storage = new ChunkStorage(regions, level.getServer().getFixerUpper(), false)) {
                CompoundTag chunk = ChunkSerializer.write(level, level.getChunk(pos.x, pos.z));
                chunk.remove(ForeignChunks.VERSION_TAG);
                storage.write(pos, chunk);
                storage.flushWorker();
            }
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(logic.foreign().contains(pos.x, pos.z), "chunk not in the snapshot");
            ForeignMap.Chunk found = logic.foreign().chunk(pos.x, pos.z);
            // Only Fabric runs its tests in a superflat world; a normal map is not raised
            boolean flat = level.getChunkSource().getGenerator() instanceof FlatLevelSource;
            assertTrue(!flat || Math.abs(found.ground()[0] - SETTINGS.foreign.flatGround) <= 8, "ground at " + found.ground()[0]);
            long before = Files.getLastModifiedTime(regions.resolve(new RegionFileName(pos).name())).toMillis();
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(before == Files.getLastModifiedTime(regions.resolve(new RegionFileName(pos).name())).toMillis(), "fitted twice");
            try (ChunkStorage storage = new ChunkStorage(regions, level.getServer().getFixerUpper(), false)) {
                CompoundTag chunk = storage.read(pos).join().orElseThrow();
                ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
                int lowest = Integer.MAX_VALUE;
                for (int i = 0; i < sections.size(); i++) {
                    CompoundTag section = sections.getCompound(i);
                    lowest = Math.min(lowest, section.getByte("Y"));
                    if (section.getByte("Y") > 0) {
                        assertTrue(!section.getCompound("block_states").toString().contains("minecraft:bedrock"), "bedrock left in the map");
                    }
                }
                assertTrue(lowest == -4, "sections start at " + lowest);
                assertTrue(!chunk.contains("Heightmaps") && !chunk.contains("blending_data"), "stale data left");
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            TerrainLogic.forget(seed, SETTINGS);
        }
        helper.succeed();
    }

    /**
     * Tags decide what a chunk is, whatever the snapshot holds: another MTM world's chunk is foreign even with this
     * algorithm version, and a new map merged in at a place already fitted gets fitted in turn.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public static void foreignChunksAreToldByTheirTags(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = SEED + 2;
        TerrainLogic logic = TerrainLogic.forWorld(seed, SETTINGS);
        ChunkPos pos = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        try {
            Path root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            CompoundTag other = ChunkSerializer.write(level, level.getChunk(pos.x, pos.z));
            other.putInt(ForeignChunks.VERSION_TAG, TerrainLogic.ALGORITHM_VERSION);
            other.putLong(ForeignChunks.WORLD_TAG, logic.worldId() + 1);
            writeChunk(level, regions, pos, other);
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(logic.foreign().contains(pos.x, pos.z), "chunk of another MTM world taken for our own");
            CompoundTag fitted = readChunk(level, regions, pos);
            assertTrue(fitted.getLong(ForeignChunks.FITTED_TAG) == logic.worldId(), "fitted chunk not marked");
            assertTrue(!fitted.contains(ForeignChunks.WORLD_TAG), "tags of the other world left");

            short before = logic.foreign().chunk(pos.x, pos.z).ground()[0];
            CompoundTag again = ChunkSerializer.write(level, level.getChunk(pos.x, pos.z));
            again.remove(ForeignChunks.VERSION_TAG);
            again.remove(ForeignChunks.WORLD_TAG);
            writeChunk(level, regions, pos, again);
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            CompoundTag merged = readChunk(level, regions, pos);
            assertTrue(merged.getLong(ForeignChunks.FITTED_TAG) == logic.worldId(), "new map at a fitted place not fitted");
            assertTrue(logic.foreign().chunk(pos.x, pos.z).ground()[0] == before, "new map not in the snapshot");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            TerrainLogic.forget(seed, SETTINGS);
        }
        helper.succeed();
    }

    /**
     * Fills below a map with real MTM terrain, while the server thread waits for it as it does when a world loads.
     * Anything in the fill that reads through the server thread hangs here instead of in a world.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void foreignChunkIsFilledWithoutTheServerThread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        ChunkPos pos = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        try {
            Path root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            CompoundTag chunk = ChunkSerializer.write(level, level.getChunk(pos.x, pos.z));
            chunk.remove(ForeignChunks.VERSION_TAG);
            chunk.remove(ForeignChunks.WORLD_TAG);
            ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
            for (int i = sections.size() - 1; i >= 0; i--) {
                if (sections.getCompound(i).getByte("Y") < 0) {
                    sections.remove(i);
                }
            }
            writeChunk(level, regions, pos, chunk);
            ForeignChunks.run(level, logic, root, world.generator(), world.random());
            CompoundTag fitted = readChunk(level, regions, pos);
            ListTag filled = fitted.getList("sections", Tag.TAG_COMPOUND);
            boolean bottom = false;
            for (int i = 0; i < filled.size(); i++) {
                bottom |= filled.getCompound(i).getByte("Y") == -4;
            }
            assertTrue(bottom, "nothing generated below the map");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            logic.setForeign(ForeignMap.EMPTY);
        }
        helper.succeed();
    }

    /** The snapshot follows the save: a foreign chunk that is cleared stops being foreign. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public static void clearedForeignChunksLeaveTheSnapshot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = SEED + 3;
        TerrainLogic logic = TerrainLogic.forWorld(seed, SETTINGS);
        ChunkPos pos = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        try {
            Path root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            CompoundTag chunk = ChunkSerializer.write(level, level.getChunk(pos.x, pos.z));
            chunk.remove(ForeignChunks.VERSION_TAG);
            chunk.remove(ForeignChunks.WORLD_TAG);
            writeChunk(level, regions, pos, chunk);
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(logic.foreign().contains(pos.x, pos.z), "chunk not in the snapshot");
            Files.delete(regions.resolve(new RegionFileName(pos).name()));
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(!logic.foreign().contains(pos.x, pos.z), "cleared chunk still in the snapshot");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            TerrainLogic.forget(seed, SETTINGS);
        }
        helper.succeed();
    }

    /** With ores turned off, the generator places no ore veins; ore features are left out where they are placed. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void oresCanBeTurnedOff(GameTestHelper helper) {
        World with = world(helper);
        World without = world(helper, WorldgenSettings.fromJson("{\"general\": {\"ores\": false}}"));
        assertTrue(((NoiseBasedChunkGenerator) with.generator()).generatorSettings().value().oreVeinsEnabled(), "ore veins off by default");
        assertTrue(!((NoiseBasedChunkGenerator) without.generator()).generatorSettings().value().oreVeinsEnabled(), "ore veins still on");
        helper.succeed();
    }

    /** The switch for falling blocks underground finds vanilla's gravel blobs, and nothing that does not fall. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void fallingOresAreFound(GameTestHelper helper) {
        Registry<PlacedFeature> placed = helper.getLevel().registryAccess().registryOrThrow(Registries.PLACED_FEATURE);
        assertTrue(MtmOres.placesFallingBlocks(placed.getOrThrow(OrePlacements.ORE_GRAVEL)), "gravel blobs not found");
        assertTrue(!MtmOres.placesFallingBlocks(placed.getOrThrow(OrePlacements.ORE_IRON_UPPER)), "iron taken for a falling block");
        assertTrue(!MtmOres.placesFallingBlocks(placed.getOrThrow(OrePlacements.ORE_DIRT)), "dirt taken for a falling block");
        helper.succeed();
    }

    /** With caves turned off, the ground is solid all the way down, and terrain above it stays as it was. */
    @GameTest(template = TEMPLATE, timeoutTicks = TIMEOUT_TICKS)
    public void cavesCanBeTurnedOff(GameTestHelper helper) {
        WorldgenSettings solid = WorldgenSettings.fromJson("{\"general\": {\"caves\": false}}");
        World plain = world(helper);
        World closed = world(helper, solid);
        int open = 0;
        for (int[] p : samplePoints()) {
            int surface = (int) Math.floor(TerrainLogic.forWorld(SEED, SETTINGS).surfaceHeight(p[0], p[1]));
            NoiseColumn caves = plain.column(p[0], p[1]);
            NoiseColumn none = closed.column(p[0], p[1]);
            for (int y = MTM_HEIGHT.getMinBuildHeight() + 8; y < surface - 16; y++) {
                open += caves.getBlock(y).isAir() ? 1 : 0;
                assertTrue(!none.getBlock(y).isAir(), "cave at " + p[0] + "," + y + "," + p[1] + " with caves off");
            }
            assertTrue(TerrainLogic.forWorld(SEED, solid).surfaceHeight(p[0], p[1]) == TerrainLogic.forWorld(SEED, SETTINGS).surfaceHeight(p[0], p[1]), "surface moved");
        }
        assertTrue(open > 0, "no caves with caves on, so this proves nothing");
        helper.succeed();
    }

    private static void writeChunk(ServerLevel level, Path regions, ChunkPos pos, CompoundTag chunk) throws IOException {
        try (ChunkStorage storage = new ChunkStorage(regions, level.getServer().getFixerUpper(), false)) {
            storage.write(pos, chunk);
            storage.flushWorker();
        }
        // File times can be too coarse to show a second write within one test
        Path file = regions.resolve(new RegionFileName(pos).name());
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000L));
    }

    private static CompoundTag readChunk(ServerLevel level, Path regions, ChunkPos pos) throws IOException {
        try (ChunkStorage storage = new ChunkStorage(regions, level.getServer().getFixerUpper(), false)) {
            return storage.read(pos).join().orElseThrow();
        }
    }

    private record RegionFileName(ChunkPos pos) {
        String name() {
            return "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new GameTestAssertException(message);
        }
    }
}
