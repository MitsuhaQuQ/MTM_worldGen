package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.TerrainCondition;
import com.mtm.worldgen.world.MtmCarving;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmWater;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

/**
 * Exposes the generator's terrain logic to MTM surface rule conditions while a chunk's surface is built, and
 * stops chunk filling at the surface of MTM worlds.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Shadow
    @Final
    protected Holder<NoiseGeneratorSettings> settings;

    @Inject(method = "buildSurface", at = @At("HEAD"))
    private void mtm_worldgen$beginSurface(WorldGenRegion region, StructureFeatureManager structures, ChunkAccess chunk,
                                           CallbackInfo ci) {
        TerrainLogic logic = MtmGenerators.terrainLogic((ChunkGenerator) (Object) this);
        if (logic != null) {
            MtmWater.fill(logic, chunk);
            TerrainCondition.begin(logic);
        }
    }

    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void mtm_worldgen$endSurface(WorldGenRegion region, StructureFeatureManager structures, ChunkAccess chunk,
                                         CallbackInfo ci) {
        TerrainCondition.end();
    }

    @Inject(method = "applyCarvers", at = @At("HEAD"))
    private void mtm_worldgen$beginCarving(WorldGenRegion region, long seed, BiomeManager biomes, StructureFeatureManager structures,
                                           ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        TerrainLogic logic = MtmGenerators.terrainLogic((ChunkGenerator) (Object) this);
        if (logic != null) {
            MtmCarving.begin(logic);
        }
    }

    @Inject(method = "applyCarvers", at = @At("RETURN"))
    private void mtm_worldgen$endCarving(WorldGenRegion region, long seed, BiomeManager biomes, StructureFeatureManager structures,
                                         ChunkAccess chunk, GenerationStep.Carving step, CallbackInfo ci) {
        MtmCarving.end();
    }

    /**
     * Limits the number of noise cells filled. Most of a tall world is air above the surface, and filling it runs
     * the interpolators, aquifer and ore vein rules on every block for nothing.
     */
    @ModifyVariable(method = "fillFromNoise", at = @At("STORE"), ordinal = 3)
    private int mtm_worldgen$limitCellsToSurface(int cellCount, Executor executor, Blender blender,
                                                 StructureFeatureManager structures, ChunkAccess chunk) {
        TerrainLogic logic = MtmGenerators.terrainLogic((ChunkGenerator) (Object) this);
        if (logic == null) {
            return cellCount;
        }
        NoiseSettings noise = settings.value().noiseSettings();
        int minY = Math.max(noise.minY(), chunk.getMinBuildHeight());
        int top = logic.highestBlockInChunk(chunk.getPos().x, chunk.getPos().z);
        int cells = Math.floorDiv(top - minY, noise.getCellHeight()) + 1;
        return Math.min(cellCount, cells);
    }
}
