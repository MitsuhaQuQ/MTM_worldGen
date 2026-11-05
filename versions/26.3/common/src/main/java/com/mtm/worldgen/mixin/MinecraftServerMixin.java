package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.BlockColumn;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmWebMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla picks the initial spawn from climate parameters only, which knows nothing about MTM terrain and
 * often lands in an ocean. For MTM worlds the terrain logic chooses the spawn column instead.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    /** The chunk vanilla searches outward from for a spawn: the first chunk position the method keeps. */
    @ModifyVariable(method = "setInitialSpawn", at = @At("STORE"), ordinal = 0)
    private static ChunkPos mtm_worldgen$findSpawn(ChunkPos origin, ServerLevel level) {
        TerrainLogic logic = MtmGenerators.terrainLogic(level);
        if (logic == null) {
            return origin;
        }
        BlockColumn spawn = logic.findSpawn();
        return ChunkPos.containing(new BlockPos(spawn.x(), 0, spawn.z()));
    }

    /** The web map stops with the server, before a world it shows is closed. */
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void mtm_worldgen$stopWebMap(CallbackInfo ci) {
        MtmWebMap.stop();
    }
}
