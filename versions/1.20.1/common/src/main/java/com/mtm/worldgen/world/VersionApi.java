package com.mtm.worldgen.world;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * The few vanilla calls whose form changes between Minecraft versions, so that the code using them stays the same in
 * every version: each version has its own copy of this file. Everything here runs on the calling thread where
 * vanilla lets it.
 */
public final class VersionApi {
    private VersionApi() {
    }

    public static ResourceLocation id(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    /** An id written as namespace:path, or as a path in the minecraft namespace. */
    public static ResourceLocation parse(String id) {
        return new ResourceLocation(id);
    }

    /** Region storage of a level's chunks in a folder. */
    public static ChunkStorage chunkStorage(ServerLevel level, Path regions) {
        return new ChunkStorage(regions, level.getServer().getFixerUpper(), false);
    }

    public static CompletableFuture<ChunkAccess> fillFromNoise(ChunkGenerator generator, Blender blender, RandomState random,
                                                               StructureManager structures, ChunkAccess chunk) {
        return generator.fillFromNoise(Runnable::run, blender, random, structures, chunk);
    }

    public static CompletableFuture<ChunkAccess> createBiomes(ChunkGenerator generator, RandomState random, Blender blender,
                                                              StructureManager structures, ChunkAccess chunk) {
        return generator.createBiomes(Runnable::run, random, blender, structures, chunk);
    }

    /** A chunk as it would be saved. */
    public static CompoundTag writeChunk(ServerLevel level, ChunkAccess chunk) {
        return ChunkSerializer.write(level, chunk);
    }

    public static <T> Registry<T> registry(RegistryAccess access, ResourceKey<? extends Registry<T>> key) {
        return access.registryOrThrow(key);
    }

    public static <T> Holder<T> holder(Registry<T> registry, ResourceKey<T> key) {
        return registry.getHolderOrThrow(key);
    }

    public static <T> T value(Registry<T> registry, ResourceKey<T> key) {
        return registry.getOrThrow(key);
    }

    /** Lowest block height of a height range. */
    public static int minY(LevelHeightAccessor height) {
        return height.getMinBuildHeight();
    }

    /** One above the highest block height of a height range. */
    public static int maxY(LevelHeightAccessor height) {
        return height.getMaxBuildHeight();
    }

    /** Stores a chunk's tag in region storage. */
    public static void store(ChunkStorage storage, ChunkPos pos, CompoundTag tag) {
        storage.write(pos, tag);
    }

    /** Registers a built-in entry. */
    public static <T> void register(WritableRegistry<T> registry, ResourceKey<T> key, T value) {
        registry.register(key, value, com.mojang.serialization.Lifecycle.stable());
    }
}
