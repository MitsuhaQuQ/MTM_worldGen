package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.ForeignChunks;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks the chunks MTM generates, which tells them from region files merged in from other worlds. */
@Mixin(ChunkSerializer.class)
abstract class ChunkSerializerMixin {
    @Inject(method = "write", at = @At("RETURN"))
    private static void mtm$stamp(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> cir) {
        TerrainLogic logic = level.dimension() == Level.OVERWORLD ? MtmGenerators.terrainLogic(level) : null;
        if (logic != null && !logic.foreign().contains(chunk.getPos().x, chunk.getPos().z)) {
            cir.getReturnValue().putInt(ForeignChunks.VERSION_TAG, TerrainLogic.ALGORITHM_VERSION);
        }
    }
}
