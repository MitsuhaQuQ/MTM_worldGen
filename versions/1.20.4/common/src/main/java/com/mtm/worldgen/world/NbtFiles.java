package com.mtm.worldgen.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;

/** Compressed NBT files, whose methods change between Minecraft versions; each version has its own copy of this. */
public final class NbtFiles {
    private NbtFiles() {
    }

    public static CompoundTag read(Path file) throws IOException {
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    public static void write(CompoundTag tag, Path file) throws IOException {
        NbtIo.writeCompressed(tag, file);
    }
}
