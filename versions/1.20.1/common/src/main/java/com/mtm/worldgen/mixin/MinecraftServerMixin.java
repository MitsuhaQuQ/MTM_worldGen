package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.BlockColumn;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla picks the initial spawn from climate parameters only, which knows nothing about MTM terrain and
 * often lands in an ocean. For MTM worlds the terrain logic chooses the spawn column instead.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Unique
    private static final ThreadLocal<ServerLevel> mtm_worldgen$spawnLevel = new ThreadLocal<>();

    @Inject(method = "setInitialSpawn", at = @At("HEAD"))
    private static void mtm_worldgen$captureLevel(ServerLevel level, ServerLevelData levelData, boolean debug,
                                                  boolean bonusChest, CallbackInfo ci) {
        mtm_worldgen$spawnLevel.set(level);
    }

    @Inject(method = "setInitialSpawn", at = @At("RETURN"))
    private static void mtm_worldgen$releaseLevel(ServerLevel level, ServerLevelData levelData, boolean debug,
                                                  boolean bonusChest, CallbackInfo ci) {
        mtm_worldgen$spawnLevel.remove();
    }

    @Redirect(method = "setInitialSpawn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Climate$Sampler;findSpawnPosition()Lnet/minecraft/core/BlockPos;"))
    private static BlockPos mtm_worldgen$findSpawn(Climate.Sampler sampler) {
        ServerLevel level = mtm_worldgen$spawnLevel.get();
        TerrainLogic logic = level != null ? MtmGenerators.terrainLogic(level) : null;
        if (logic == null) {
            return sampler.findSpawnPosition();
        }
        BlockColumn spawn = logic.findSpawn();
        return new BlockPos(spawn.x(), 0, spawn.z());
    }
}
