package com.mtm.worldgen.benchmark;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

/**
 * Compares MTM with vanilla on the biome and noise stages of chunk generation, single threaded, on the same chunk
 * positions. Surface, carver and feature stages need a live world and are not covered.
 */
public final class GenerationBenchmark {
    private static final long SEED = 20261029L;
    private static final int CHUNKS_PER_AXIS = 8;
    /** Spread the sample so it crosses several landforms instead of one uniform patch. */
    private static final int CHUNK_STRIDE = 32;
    private static final int WARMUP_PASSES = 3;
    private static final int MEASURED_PASSES = 3;
    private static final int VANILLA_MIN_Y = -64;
    private static final int VANILLA_HEIGHT = 384;

    private static final Executor SAME_THREAD = Runnable::run;

    private GenerationBenchmark() {
    }

    /** Milliseconds per chunk for each measured stage. */
    public record StageTimes(double biomesMs, double noiseMs) {
        double totalMs() {
            return biomesMs + noiseMs;
        }
    }

    /**
     * @param vanilla      vanilla stage times
     * @param mtm          MTM stage times with warm logic caches
     * @param mtmCold      MTM stage times starting from empty logic caches
     * @param logicStartMs milliseconds to build the terrain logic for a seed
     */
    public record Result(StageTimes vanilla, StageTimes mtm, StageTimes mtmCold, double logicStartMs) {
        public String summary() {
            return String.format(Locale.ROOT,
                    "vanilla %.2f ms/chunk (biomes %.2f, noise %.2f), MTM %.2f ms/chunk (biomes %.2f, noise %.2f), "
                            + "%.0f%% of vanilla, cold %.2f ms/chunk, logic start %.0f ms",
                    vanilla.totalMs(), vanilla.biomesMs(), vanilla.noiseMs(), mtm.totalMs(), mtm.biomesMs(), mtm.noiseMs(),
                    100.0 * mtm.totalMs() / vanilla.totalMs(), mtmCold.totalMs(), logicStartMs);
        }
    }

    public static Result run(ServerLevel level) {
        RegistryAccess access = level.registryAccess();
        StructureManager structures = noStructures(level);
        Holder<NoiseGeneratorSettings> overworld = access.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        LevelHeightAccessor vanillaHeight = LevelHeightAccessor.create(VANILLA_MIN_Y, VANILLA_HEIGHT);
        WorldgenSettings settings = WorldgenSettings.DEFAULT;
        LevelHeightAccessor mtmHeight = LevelHeightAccessor.create(settings.minBuildY(), settings.buildHeight());

        ChunkGenerator vanilla = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(access
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), overworld);
        StageTimes vanillaTimes = measure(vanilla, MtmGenerators.randomState(access, vanilla, SEED), vanillaHeight, access, structures);

        TerrainLogic.forget(SEED, settings);
        long start = System.nanoTime();
        ChunkGenerator mtm = MtmGenerators.create(access, overworld, settings);
        RandomState mtmRandom = MtmGenerators.randomState(access, mtm, SEED);
        double logicStartMs = (System.nanoTime() - start) / 1.0e6;
        StageTimes mtmCold = pass(mtm, mtmRandom, mtmHeight, access, structures);
        StageTimes mtmTimes = measure(mtm, mtmRandom, mtmHeight, access, structures);
        return new Result(vanillaTimes, mtmTimes, mtmCold, logicStartMs);
    }

    /**
     * A structure manager that reports no structures. The level's own manager loads real chunks, which needs the
     * server thread; generation stages wait on worker tasks, so that deadlocks when called from the server thread.
     */
    public static StructureManager noStructures(ServerLevel level) {
        return new StructureManager(level, level.getServer().getWorldData().worldGenOptions(), null) {
            @Override
            public List<StructureStart> startsForStructure(ChunkPos pos, Predicate<Structure> predicate) {
                return List.of();
            }

            @Override
            public List<StructureStart> startsForStructure(SectionPos pos, Structure structure) {
                return List.of();
            }
        };
    }

    private static StageTimes measure(ChunkGenerator generator, RandomState random, LevelHeightAccessor height,
                                      RegistryAccess access, StructureManager structures) {
        for (int i = 0; i < WARMUP_PASSES; i++) {
            pass(generator, random, height, access, structures);
        }
        StageTimes best = null;
        for (int i = 0; i < MEASURED_PASSES; i++) {
            StageTimes times = pass(generator, random, height, access, structures);
            if (best == null || times.totalMs() < best.totalMs()) {
                best = times;
            }
        }
        return best;
    }

    /** Generates every sample chunk once and returns milliseconds per chunk for each stage. */
    private static StageTimes pass(ChunkGenerator generator, RandomState random, LevelHeightAccessor height,
                                   RegistryAccess access, StructureManager structures) {
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        long biomeNanos = 0L;
        long noiseNanos = 0L;
        for (int cx = 0; cx < CHUNKS_PER_AXIS; cx++) {
            for (int cz = 0; cz < CHUNKS_PER_AXIS; cz++) {
                ChunkPos pos = new ChunkPos(cx * CHUNK_STRIDE, cz * CHUNK_STRIDE);
                ChunkAccess chunk = new ProtoChunk(pos, UpgradeData.EMPTY, height, biomes, null);
                long start = System.nanoTime();
                generator.createBiomes(SAME_THREAD, random, Blender.empty(), structures, chunk).join();
                long afterBiomes = System.nanoTime();
                generator.fillFromNoise(SAME_THREAD, Blender.empty(), random, structures, chunk).join();
                biomeNanos += afterBiomes - start;
                noiseNanos += System.nanoTime() - afterBiomes;
            }
        }
        double perChunk = 1.0e6 * CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;
        return new StageTimes(biomeNanos / perChunk, noiseNanos / perChunk);
    }
}
