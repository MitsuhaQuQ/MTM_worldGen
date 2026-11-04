package com.mtm.worldgen.gametest;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.benchmark.GenerationBenchmark;
import com.mtm.worldgen.logic.BlockColumn;
import com.mtm.worldgen.logic.ForeignMap;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.platform.Platform;
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
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
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
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * In-game checks of the full MTM generation path: seed mixin, density functions, aquifer and biome source. The game
 * test server always runs a flat world, so each test builds its own MTM generator and samples it with
 * NoiseBasedChunkGenerator#getBaseColumn, which computes block columns without generating chunks.
 *
 * <p>Shared by all loaders; the expected column hash is the same constant on every loader of this Minecraft version,
 * which is how the tests prove the loaders generate identical terrain.
 *
 * <p>Since 1.21.5 game tests are registry entries: each loader registers the functions {@link #register} lists as test
 * functions, and the test instances that run them are data, in data/mtm_worldgen/test_instance.
 */
public final class MtmGameTests {
    private static final long SEED = 20261029L;
    /** Fixed defaults, so a local config file cannot change what the tests and the golden hash see. */
    private static final WorldgenSettings SETTINGS = WorldgenSettings.DEFAULT;
    private static final int SAMPLE_ORIGIN = -6000;
    private static final int SAMPLE_STEP = 397;
    private static final int SAMPLES_PER_AXIS = 32;
    /**
     * Hash of all sampled columns for {@link #SEED}, the same on every loader and every Minecraft version. Changes
     * whenever terrain generation changes on purpose: update it then in every version's tests, together with
     * LogicRegion.ALGORITHM_VERSION if saved worlds are affected.
     */
    private static final long EXPECTED_COLUMN_HASH = 0xd51e25e379d7fa56L;
    /**
     * The same hash for a world without caves, which leaves out vanilla's cave noise and so holds where Minecraft does
     * not run as Mojang built it: NeoForge's development environment recompiles it from decompiled sources, and floats
     * in vanilla's cave noise come out a bit apart, which moves a few cave walls by a block.
     */
    private static final long EXPECTED_SOLID_COLUMN_HASH = 0x072ebb2d2dbe9343L;
    /** Settings of {@link #EXPECTED_SOLID_COLUMN_HASH}. */
    private static final WorldgenSettings SOLID_SETTINGS = WorldgenSettings.fromJson("{\"general\": {\"caves\": false}}");

    private static final int SEA_SURFACE_Y = 62;
    private static final double DEEP_OCEAN_COAST_DISTANCE = -1200.0;
    private static final double FAR_INLAND_COAST_DISTANCE = 1200.0;
    /** One block apart, mountains up to 150% grade can differ by 2 blocks after rounding; more is a seam. */
    private static final int MAX_BORDER_STEP = 2;

    /** MTM build range. The game test level is a vanilla-height flat world, so columns are sampled with this. */
    /** A generator with the seed bound to it, which vanilla keeps apart. */
    private record World(ChunkGenerator generator, RandomState random) {
        BaseColumn column(int x, int z) {
            return BaseColumn.of(generator, x, z, MTM_HEIGHT, random);
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
        public int getMinY() {
            return SETTINGS.minBuildY();
        }
    };

    public static void columnsMatchExpectedHash(GameTestHelper helper) {
        TerrainLogic.forget(SEED, SETTINGS);
        long solid = hashColumns(world(helper, SOLID_SETTINGS), samplePoints());
        if (solid != EXPECTED_SOLID_COLUMN_HASH) {
            fail(helper, "Column hash without caves 0x" + Long.toHexString(solid) + " != expected 0x"
                    + Long.toHexString(EXPECTED_SOLID_COLUMN_HASH) + " (terrain changed, or loaders disagree)");
        }
        TerrainLogic.forget(SEED, SETTINGS);
        long actual = hashColumns(world(helper), samplePoints());
        // Vanilla's caves only match where Minecraft runs as Mojang built it
        if (Platform.get().recompilesMinecraft()) {
            MtmWorldgen.LOGGER.info("Column hash with caves 0x{}, not checked: this Minecraft is recompiled", Long.toHexString(actual));
        } else if (actual != EXPECTED_COLUMN_HASH) {
            fail(helper, "Column hash 0x" + Long.toHexString(actual) + " != expected 0x"
                    + Long.toHexString(EXPECTED_COLUMN_HASH) + " (terrain changed, or loaders disagree)");
        }
        helper.succeed();
    }

    public static void columnsDoNotDependOnSamplingOrder(GameTestHelper helper) {
        List<int[]> points = samplePoints();

        TerrainLogic.forget(SEED, SETTINGS);
        List<Long> forward = columnHashes(world(helper), points);

        TerrainLogic.forget(SEED, SETTINGS);
        List<int[]> reversed = new ArrayList<>(points);
        Collections.reverse(reversed);
        List<Long> backward = columnHashes(world(helper), reversed);
        Collections.reverse(backward);

        if (!forward.equals(backward)) {
            fail(helper, "Columns differ when sampled in reverse order with cold caches");
        }
        helper.succeed();
    }

    public static void columnsDoNotDependOnThreads(GameTestHelper helper) throws Exception {
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
                    fail(helper, "Column at " + points.get(i)[0] + "," + points.get(i)[1] + " differs when computed concurrently");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        helper.succeed();
    }

    public static void surfaceIsContinuousAcrossRegionBorders(GameTestHelper helper) {
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
                fail(helper, "Surface jumps from " + west + " to " + east + " across the region border at z=" + z);
            }
        }
        helper.succeed();
    }

    /** Regression check for dry land below sea level next to walls of sea water. */
    public static void landBelowSeaLevelIsFlooded(GameTestHelper helper) {
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        for (int[] p : samplePoints()) {
            // Under land, air at this height is a cave
            if (logic.surfaceHeight(p[0], p[1]) >= SEA_SURFACE_Y) {
                continue;
            }
            BaseColumn column = world.column(p[0], p[1]);
            for (int y = SEA_SURFACE_Y; y >= MTM_HEIGHT.getMinY(); y--) {
                BlockState state = column.getBlock(y);
                if (!state.isAir()) {
                    break;
                }
                fail(helper, "Dry air at " + p[0] + "," + y + "," + p[1] + " below sea level");
            }
        }
        helper.succeed();
    }

    public static void biomesFollowTerrain(GameTestHelper helper) {
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        int checkedOcean = 0;
        int checkedInland = 0;
        for (int[] p : samplePoints()) {
            double coast = logic.coastDistance(p[0], p[1]);
            int surfaceY = (int) Math.floor(logic.surfaceHeight(p[0], p[1]));
            Holder<Biome> biome = world.generator().getBiomeSource().createUncachedResolver(world.random()).getNoiseBiome(QuartPos.fromBlock(p[0]),
                    QuartPos.fromBlock(Math.max(surfaceY, SEA_SURFACE_Y)), QuartPos.fromBlock(p[1]));
            if (coast < DEEP_OCEAN_COAST_DISTANCE) {
                checkedOcean++;
                if (!biome.is(BiomeTags.IS_OCEAN)) {
                    fail(helper, "Non-ocean biome " + name(biome) + " in open ocean at " + p[0] + "," + p[1]);
                }
            } else if (coast > FAR_INLAND_COAST_DISTANCE) {
                checkedInland++;
                if (biome.is(BiomeTags.IS_OCEAN) || biome.is(Biomes.DRIPSTONE_CAVES) || biome.is(Biomes.LUSH_CAVES)) {
                    fail(helper, "Biome " + name(biome) + " on inland surface at " + p[0] + "," + surfaceY + "," + p[1]);
                }
            }
        }
        if (checkedOcean == 0 || checkedInland == 0) {
            fail(helper, "Sample area has no open ocean or no far inland points; move the sample area");
        }
        helper.succeed();
    }

    /**
     * Worlds are created and loaded through the generator codec, which wraps density functions in holders.
     * Spawn placement and the locate command must still recognize the decoded generator as MTM.
     */
    public static void generatorIsRecognizedAfterSaveAndLoad(GameTestHelper helper) {
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        JsonElement saved = ChunkGenerator.CODEC.encodeStart(ops, world(helper).generator())
                .getOrThrow(message -> new IllegalStateException("Encoding failed: " + message));
        ChunkGenerator loaded = ChunkGenerator.CODEC.parse(ops, saved)
                .getOrThrow(message -> new IllegalStateException("Decoding failed: " + message));
        if (MtmGenerators.terrainLogic(MtmGenerators.randomState(helper.getLevel().registryAccess(), loaded, SEED)) == null) {
            fail(helper, "Decoded generator is not recognized as an MTM generator");
        }
        helper.succeed();
    }

    /** The world preset is a placeholder until the config applies; both world creation paths go through this. */
    public static void worldPresetBuildsMtmWorld(GameTestHelper helper) {
        RegistryAccess access = helper.getLevel().registryAccess();
        WorldDimensions dimensions = access.lookupOrThrow(Registries.WORLD_PRESET)
                .getValueOrThrow(ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "realistic")))
                .createWorldDimensions();
        ChunkGenerator generator = dimensions.overworld();
        if (MtmGenerators.terrainLogic(MtmGenerators.randomState(access, generator, SEED)) == null) {
            fail(helper, "The world preset did not build an MTM generator");
        }
        DimensionType type = dimensions.get(LevelStem.OVERWORLD).orElseThrow().type().value();
        NoiseSettings noise = ((NoiseBasedChunkGenerator) generator).generatorSettings().value().noiseSettings();
        if (type.minY() != noise.minY() || type.height() != noise.height()) {
            fail(helper, "Dimension type height " + type.height() + " does not match generator height " + noise.height());
        }
        helper.succeed();
    }

    /**
     * Chunk filling stops above the surface as an optimization, a path the column based tests do not cover. Fills
     * real chunks on flat land and on a mountain and compares them with full height columns, block by block.
     */
    public static void filledChunksMatchColumns(GameTestHelper helper) {
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        PalettedContainerFactory containers = PalettedContainerFactory.create(helper.getLevel().registryAccess());
        StructureManager structures = GenerationBenchmark.noStructures(helper.getLevel());

        BlockColumn mountain = logic.findNearestAbove(SETTINGS.general.maxSurfaceHeight / 2.0, 0, 0, 64, 16384);
        if (mountain == null) {
            fail(helper, "No mountain found near the origin to test against");
        }
        for (ChunkPos pos : List.of(new ChunkPos(0, 0), ChunkPos.containing(new BlockPos(mountain.x(), 0, mountain.z())))) {
            ChunkAccess chunk = new ProtoChunk(pos, UpgradeData.EMPTY, MTM_HEIGHT, containers, null);
            MtmGenerators.fillTerrain(world.generator(), world.random(), structures, chunk);
            for (int[] offset : new int[][]{{0, 0}, {7, 7}, {15, 15}, {3, 12}}) {
                int x = pos.getMinBlockX() + offset[0];
                int z = pos.getMinBlockZ() + offset[1];
                BaseColumn column = world.column(x, z);
                for (int y = MTM_HEIGHT.getMinY(); y <= MTM_HEIGHT.getMaxY(); y++) {
                    BlockState filled = chunk.getBlockState(new BlockPos(x, y, z));
                    if (filled.getBlock() != column.getBlock(y).getBlock()) {
                        fail(helper, "Chunk has " + filled + " but the column has " + column.getBlock(y)
                                + " at " + x + "," + y + "," + z);
                    }
                }
            }
        }
        helper.succeed();
    }

    /** Logs generation speed compared with vanilla. Timing varies by machine, so it reports and never fails. */
    public static void reportGenerationBenchmark(GameTestHelper helper) {
        MtmWorldgen.LOGGER.info("Benchmark: {}", GenerationBenchmark.run(helper.getLevel()).summary());
        helper.succeed();
    }

    /** Names every test function, as test_instance files refer to them, with the function itself. */
    public static void register(BiConsumer<String, Consumer<GameTestHelper>> registrar) {
        registrar.accept("columns_match_expected_hash", MtmGameTests::columnsMatchExpectedHash);
        registrar.accept("columns_do_not_depend_on_sampling_order", MtmGameTests::columnsDoNotDependOnSamplingOrder);
        registrar.accept("columns_do_not_depend_on_threads", helper -> {
            try {
                columnsDoNotDependOnThreads(helper);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        registrar.accept("surface_is_continuous_across_region_borders", MtmGameTests::surfaceIsContinuousAcrossRegionBorders);
        registrar.accept("land_below_sea_level_is_flooded", MtmGameTests::landBelowSeaLevelIsFlooded);
        registrar.accept("biomes_follow_terrain", MtmGameTests::biomesFollowTerrain);
        registrar.accept("generator_is_recognized_after_save_and_load", MtmGameTests::generatorIsRecognizedAfterSaveAndLoad);
        registrar.accept("world_preset_builds_mtm_world", MtmGameTests::worldPresetBuildsMtmWorld);
        registrar.accept("filled_chunks_match_columns", MtmGameTests::filledChunksMatchColumns);
        registrar.accept("report_generation_benchmark", MtmGameTests::reportGenerationBenchmark);
        registrar.accept("foreign_flat_chunk_is_fitted", MtmGameTests::foreignFlatChunkIsFitted);
        registrar.accept("foreign_chunks_are_told_by_their_tags", MtmGameTests::foreignChunksAreToldByTheirTags);
        registrar.accept("foreign_chunk_is_filled_without_the_server_thread", MtmGameTests::foreignChunkIsFilledWithoutTheServerThread);
        registrar.accept("cleared_foreign_chunks_leave_the_snapshot", MtmGameTests::clearedForeignChunksLeaveTheSnapshot);
        registrar.accept("ores_can_be_turned_off", MtmGameTests::oresCanBeTurnedOff);
        registrar.accept("falling_ores_are_found", MtmGameTests::fallingOresAreFound);
        registrar.accept("caves_can_be_turned_off", MtmGameTests::cavesCanBeTurnedOff);
    }

    private static World world(GameTestHelper helper) {
        return world(helper, SETTINGS);
    }

    private static World world(GameTestHelper helper, WorldgenSettings settings) {
        RegistryAccess access = helper.getLevel().registryAccess();
        ChunkGenerator generator = MtmGenerators.create(access,
                access.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD), settings);
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

    /**
     * Blocks of vanilla's ore veins. Up to 1.21.x the noise placed them, so base columns show them; from 26.1 the
     * material rules do, after the base column. The hash counts them as stone, so it stays the same in every version.
     */
    private static final java.util.Set<String> ORE_VEIN_BLOCKS = java.util.Set.of("minecraft:copper_ore",
            "minecraft:raw_copper_block", "minecraft:granite", "minecraft:deepslate_iron_ore", "minecraft:raw_iron_block",
            "minecraft:tuff");

    /** Hashes block registry names, not numeric state ids, which may differ between loaders. */
    private static long hashColumn(BaseColumn column) {
        long hash = 17L;
        for (int y = MTM_HEIGHT.getMinY(); y <= MTM_HEIGHT.getMaxY(); y++) {
            BlockState state = column.getBlock(y);
            String block = state.is(Blocks.AIR) ? "air" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            hash = hash * 1_000_003L + (ORE_VEIN_BLOCKS.contains(block) ? "minecraft:stone" : block).hashCode();
        }
        return hash;
    }

    private static String name(Holder<Biome> biome) {
        return biome.unwrapKey().map(ResourceKey::identifier).map(Object::toString).orElse("?");
    }

    /**
     * Runs a real superflat chunk of the test world through the fitting of foreign chunks: it has to come back raised
     * to the target ground, with no vanilla bedrock left in it and rock generated below, and only once.
     */
    public static void foreignFlatChunkIsFitted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = SEED + 1;
        TerrainLogic logic = TerrainLogic.forWorld(seed, SETTINGS);
        ChunkPos pos = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        Path root;
        try {
            root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            try (SimpleRegionStorage storage = storage(level, regions)) {
                CompoundTag chunk = SerializableChunkData.copyOf(level, level.getChunk(pos.x(), pos.z())).write();
                chunk.remove(ForeignChunks.VERSION_TAG);
                storage.write(pos, chunk);
                storage.synchronize(true).join();
            }
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(logic.foreign().contains(pos.x(), pos.z()), "chunk not in the snapshot");
            ForeignMap.Chunk found = logic.foreign().chunk(pos.x(), pos.z());
            // Only Fabric runs its tests in a superflat world; a normal map is not raised
            boolean flat = level.getChunkSource().getGenerator() instanceof FlatLevelSource;
            assertTrue(!flat || Math.abs(found.ground()[0] - SETTINGS.foreign.flatGround) <= 8, "ground at " + found.ground()[0]);
            long before = Files.getLastModifiedTime(regions.resolve(new RegionFileName(pos).name())).toMillis();
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(before == Files.getLastModifiedTime(regions.resolve(new RegionFileName(pos).name())).toMillis(), "fitted twice");
            try (SimpleRegionStorage storage = storage(level, regions)) {
                CompoundTag chunk = storage.read(pos).join().orElseThrow();
                ListTag sections = chunk.getListOrEmpty("sections");
                int lowest = Integer.MAX_VALUE;
                for (int i = 0; i < sections.size(); i++) {
                    CompoundTag section = sections.getCompoundOrEmpty(i);
                    lowest = Math.min(lowest, section.getByteOr("Y", (byte) 0));
                    if (section.getByteOr("Y", (byte) 0) > 0) {
                        assertTrue(!section.getCompoundOrEmpty("block_states").toString().contains("minecraft:bedrock"), "bedrock left in the map");
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
    public static void foreignChunksAreToldByTheirTags(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = SEED + 2;
        TerrainLogic logic = TerrainLogic.forWorld(seed, SETTINGS);
        ChunkPos pos = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        try {
            Path root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            CompoundTag other = SerializableChunkData.copyOf(level, level.getChunk(pos.x(), pos.z())).write();
            other.putInt(ForeignChunks.VERSION_TAG, TerrainLogic.ALGORITHM_VERSION);
            other.putLong(ForeignChunks.WORLD_TAG, logic.worldId() + 1);
            writeChunk(level, regions, pos, other);
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(logic.foreign().contains(pos.x(), pos.z()), "chunk of another MTM world taken for our own");
            CompoundTag fitted = readChunk(level, regions, pos);
            assertTrue(fitted.getLongOr(ForeignChunks.FITTED_TAG, 0L) == logic.worldId(), "fitted chunk not marked");
            assertTrue(!fitted.contains(ForeignChunks.WORLD_TAG), "tags of the other world left");

            short before = logic.foreign().chunk(pos.x(), pos.z()).ground()[0];
            CompoundTag again = SerializableChunkData.copyOf(level, level.getChunk(pos.x(), pos.z())).write();
            again.remove(ForeignChunks.VERSION_TAG);
            again.remove(ForeignChunks.WORLD_TAG);
            writeChunk(level, regions, pos, again);
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            CompoundTag merged = readChunk(level, regions, pos);
            assertTrue(merged.getLongOr(ForeignChunks.FITTED_TAG, 0L) == logic.worldId(), "new map at a fitted place not fitted");
            assertTrue(logic.foreign().chunk(pos.x(), pos.z()).ground()[0] == before, "new map not in the snapshot");
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
    public static void foreignChunkIsFilledWithoutTheServerThread(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        World world = world(helper);
        TerrainLogic logic = TerrainLogic.forWorld(SEED, SETTINGS);
        ChunkPos pos = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        try {
            Path root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            CompoundTag chunk = SerializableChunkData.copyOf(level, level.getChunk(pos.x(), pos.z())).write();
            chunk.remove(ForeignChunks.VERSION_TAG);
            chunk.remove(ForeignChunks.WORLD_TAG);
            ListTag sections = chunk.getListOrEmpty("sections");
            for (int i = sections.size() - 1; i >= 0; i--) {
                if (sections.getCompoundOrEmpty(i).getByteOr("Y", (byte) 0) < 0) {
                    sections.remove(i);
                }
            }
            writeChunk(level, regions, pos, chunk);
            ForeignChunks.run(level, logic, root, world.generator(), world.random());
            CompoundTag fitted = readChunk(level, regions, pos);
            ListTag filled = fitted.getListOrEmpty("sections");
            boolean bottom = false;
            for (int i = 0; i < filled.size(); i++) {
                bottom |= filled.getCompoundOrEmpty(i).getByteOr("Y", (byte) 0) == -4;
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
    public static void clearedForeignChunksLeaveTheSnapshot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long seed = SEED + 3;
        TerrainLogic logic = TerrainLogic.forWorld(seed, SETTINGS);
        ChunkPos pos = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        try {
            Path root = Files.createTempDirectory("mtm_foreign");
            Path regions = root.resolve("region");
            Files.createDirectories(regions);
            CompoundTag chunk = SerializableChunkData.copyOf(level, level.getChunk(pos.x(), pos.z())).write();
            chunk.remove(ForeignChunks.VERSION_TAG);
            chunk.remove(ForeignChunks.WORLD_TAG);
            writeChunk(level, regions, pos, chunk);
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(logic.foreign().contains(pos.x(), pos.z()), "chunk not in the snapshot");
            Files.delete(regions.resolve(new RegionFileName(pos).name()));
            ForeignChunks.run(level, logic, root, level.getChunkSource().getGenerator(), level.getChunkSource().randomState());
            assertTrue(!logic.foreign().contains(pos.x(), pos.z()), "cleared chunk still in the snapshot");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            TerrainLogic.forget(seed, SETTINGS);
        }
        helper.succeed();
    }

    /** With ores turned off, the generator places no ore veins; ore features are left out where they are placed. */
    public static void oresCanBeTurnedOff(GameTestHelper helper) {
        World with = world(helper);
        World without = world(helper, WorldgenSettings.fromJson("{\"general\": {\"ores\": false}}"));
        assertTrue(MtmGenerators.hasOreVeins(((NoiseBasedChunkGenerator) with.generator()).generatorSettings().value()), "ore veins off by default");
        assertTrue(!MtmGenerators.hasOreVeins(((NoiseBasedChunkGenerator) without.generator()).generatorSettings().value()), "ore veins still on");
        helper.succeed();
    }

    /** The switch for falling blocks underground finds vanilla's gravel blobs, and nothing that does not fall. */
    public static void fallingOresAreFound(GameTestHelper helper) {
        Registry<PlacedFeature> placed = helper.getLevel().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        assertTrue(MtmOres.placesFallingBlocks(placed.getValueOrThrow(OrePlacements.ORE_GRAVEL)), "gravel blobs not found");
        assertTrue(!MtmOres.placesFallingBlocks(placed.getValueOrThrow(OrePlacements.ORE_IRON_UPPER)), "iron taken for a falling block");
        assertTrue(!MtmOres.placesFallingBlocks(placed.getValueOrThrow(OrePlacements.ORE_DIRT)), "dirt taken for a falling block");
        helper.succeed();
    }

    /** With caves turned off, the ground is solid all the way down, and terrain above it stays as it was. */
    public static void cavesCanBeTurnedOff(GameTestHelper helper) {
        WorldgenSettings solid = WorldgenSettings.fromJson("{\"general\": {\"caves\": false}}");
        World plain = world(helper);
        World closed = world(helper, solid);
        int open = 0;
        for (int[] p : samplePoints()) {
            int surface = (int) Math.floor(TerrainLogic.forWorld(SEED, SETTINGS).surfaceHeight(p[0], p[1]));
            BaseColumn caves = plain.column(p[0], p[1]);
            BaseColumn none = closed.column(p[0], p[1]);
            for (int y = MTM_HEIGHT.getMinY() + 8; y < surface - 16; y++) {
                open += caves.getBlock(y).isAir() ? 1 : 0;
                assertTrue(!none.getBlock(y).isAir(), "cave at " + p[0] + "," + y + "," + p[1] + " with caves off");
            }
            assertTrue(TerrainLogic.forWorld(SEED, solid).surfaceHeight(p[0], p[1]) == TerrainLogic.forWorld(SEED, SETTINGS).surfaceHeight(p[0], p[1]), "surface moved");
        }
        assertTrue(open > 0, "no caves with caves on, so this proves nothing");
        helper.succeed();
    }

    private static SimpleRegionStorage storage(ServerLevel level, Path regions) {
        return new SimpleRegionStorage(new RegionStorageInfo("test", Level.OVERWORLD, "chunk"), regions,
                level.getServer().getFixerUpper(), false, DataFixTypes.CHUNK);
    }

    private static void writeChunk(ServerLevel level, Path regions, ChunkPos pos, CompoundTag chunk) throws IOException {
        try (SimpleRegionStorage storage = storage(level, regions)) {
            storage.write(pos, chunk);
            storage.synchronize(true).join();
        }
        // File times can be too coarse to show a second write within one test
        Path file = regions.resolve(new RegionFileName(pos).name());
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000L));
    }

    private static CompoundTag readChunk(ServerLevel level, Path regions, ChunkPos pos) throws IOException {
        try (SimpleRegionStorage storage = storage(level, regions)) {
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
            throw new GameTestAssertException(Component.literal(message), 0);
        }
    }

    private static void fail(GameTestHelper helper, String message) {
        throw helper.assertionException(Component.literal(message));
    }
}
