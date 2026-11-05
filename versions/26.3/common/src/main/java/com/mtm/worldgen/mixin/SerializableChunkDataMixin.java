package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.ForeignMap;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.ChunkStamp;
import com.mtm.worldgen.world.ForeignChunks;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/** Marks the chunks of a world, which tells them from region files merged in from other worlds, MTM or not. */
@Mixin(SerializableChunkData.class)
abstract class SerializableChunkDataMixin implements ChunkStamp {
    @Unique
    private Consumer<CompoundTag> mtm$stamp;

    @Override
    public void mtm$setStamp(Consumer<CompoundTag> stamp) {
        mtm$stamp = stamp;
    }

    @Inject(method = "copyOf", at = @At("RETURN"))
    private static void mtm$takeStamp(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<SerializableChunkData> cir) {
        TerrainLogic logic = level.dimension() == Level.OVERWORLD ? MtmGenerators.terrainLogic(level) : null;
        if (logic == null) {
            return;
        }
        long world = logic.worldId();
        ForeignMap.Chunk foreign = logic.foreign().chunk(chunk.getPos().x(), chunk.getPos().z());
        boolean upgraded = foreign != null && foreign.upgraded();
        boolean fitted = foreign != null && !upgraded;
        ((ChunkStamp) (Object) cir.getReturnValue()).mtm$setStamp(tag -> {
            if (upgraded) {
                // Still this world's own, from an older version: it stays one when saved again
                tag.putInt(ForeignChunks.VERSION_TAG, ForeignChunks.OLDER_VERSION);
                tag.putLong(ForeignChunks.WORLD_TAG, world);
            } else if (fitted) {
                tag.putLong(ForeignChunks.FITTED_TAG, world);
            } else {
                tag.putInt(ForeignChunks.VERSION_TAG, TerrainLogic.ALGORITHM_VERSION);
                tag.putLong(ForeignChunks.WORLD_TAG, world);
            }
        });
    }

    @Inject(method = "write", at = @At("RETURN"))
    private void mtm$stamp(CallbackInfoReturnable<CompoundTag> cir) {
        if (mtm$stamp != null) {
            mtm$stamp.accept(cir.getReturnValue());
        }
    }
}
