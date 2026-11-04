package com.mtm.worldgen.mixin;

import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Fills a chunk from the noise alone, on the calling thread. Vanilla only offers that as part of building terrain,
 * which also lays the surface and carves, and runs on another thread.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public interface NoiseFillAccess {
    @Invoker("createNoiseChunk")
    NoiseChunk mtm$createNoiseChunk(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState random, NoiseSettings noise);

    @Invoker("doFill")
    void mtm$doFill(NoiseChunk noiseChunk, ChunkAccess chunk);
}
