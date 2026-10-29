package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.BeachCondition;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exposes the generator's terrain logic to MTM surface rule conditions while a chunk's surface is built. */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(method = "buildSurface", at = @At("HEAD"))
    private void mtm_worldgen$beginSurface(WorldGenRegion region, StructureFeatureManager structures, ChunkAccess chunk,
                                           CallbackInfo ci) {
        TerrainLogic logic = MtmGenerators.terrainLogic((ChunkGenerator) (Object) this);
        if (logic != null) {
            BeachCondition.begin(logic);
        }
    }

    @Inject(method = "buildSurface", at = @At("RETURN"))
    private void mtm_worldgen$endSurface(WorldGenRegion region, StructureFeatureManager structures, ChunkAccess chunk,
                                         CallbackInfo ci) {
        BeachCondition.end();
    }
}
