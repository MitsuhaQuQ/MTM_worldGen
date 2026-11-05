package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.ForeignChunks;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmSnow;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the MTM snow line to the snow and ice that weather adds to loaded chunks, which 1.20.3 moved from tickChunk
 * to tickPrecipitation.
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void mtm$fitForeignChunks(CallbackInfo ci) {
        MtmGenerators.useCacheDirectory((ServerLevel) (Object) this);
        ForeignChunks.prepare((ServerLevel) (Object) this);
    }

    @Redirect(method = "tickPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean mtm$shouldFreeze(Biome biome, LevelReader level, BlockPos pos) {
        return !MtmSnow.tooWarm((ServerLevel) (Object) this, biome, pos) && biome.shouldFreeze(level, pos);
    }

    @Redirect(method = "tickPrecipitation", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean mtm$shouldSnow(Biome biome, LevelReader level, BlockPos pos) {
        return !MtmSnow.tooWarm((ServerLevel) (Object) this, biome, pos) && biome.shouldSnow(level, pos);
    }
}
