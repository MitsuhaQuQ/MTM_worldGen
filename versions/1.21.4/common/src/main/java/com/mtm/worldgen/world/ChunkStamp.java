package com.mtm.worldgen.world;

import net.minecraft.nbt.CompoundTag;

import java.util.function.Consumer;

/**
 * Added to SerializableChunkData by a mixin: the MTM marks of a chunk, taken when the chunk is copied for saving on the
 * server thread and written with the rest of its data, which may happen elsewhere.
 */
public interface ChunkStamp {
    void mtm$setStamp(Consumer<CompoundTag> stamp);
}
