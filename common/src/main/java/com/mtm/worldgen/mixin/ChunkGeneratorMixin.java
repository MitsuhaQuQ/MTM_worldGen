package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureFeatureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.VillageFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps villages of MTM worlds on flat ground. */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void mtm$rejectSteepVillage(StructureSet.StructureSelectionEntry entry, StructureFeatureManager features,
                                        RegistryAccess access, StructureManager structures, long seed, ChunkAccess chunk,
                                        ChunkPos chunkPos, SectionPos section, CallbackInfoReturnable<Boolean> cir) {
        if (!(entry.structure().value().feature instanceof VillageFeature)) {
            return;
        }
        TerrainLogic logic = MtmGenerators.terrainLogic((ChunkGenerator) (Object) this);
        if (logic != null && !logic.isVillageGround(chunkPos.getMiddleBlockX(), chunkPos.getMiddleBlockZ())) {
            cir.setReturnValue(false);
        }
    }
}
