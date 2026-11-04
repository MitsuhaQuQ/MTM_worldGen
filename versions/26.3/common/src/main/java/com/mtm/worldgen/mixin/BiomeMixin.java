package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmSnow;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the MTM snow line to snow and ice, both those placed during world generation and those weather adds to loaded
 * chunks: both ask the biome here, with the level at hand. Asked about a client's level, the biome answers as vanilla.
 */
@Mixin(Biome.class)
abstract class BiomeMixin {
    @Inject(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z", at = @At("HEAD"), cancellable = true)
    private void mtm$shouldFreeze(LevelReader level, BlockPos pos, boolean checkNeighbors, CallbackInfoReturnable<Boolean> cir) {
        if (mtm$tooWarm(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldSnow", at = @At("HEAD"), cancellable = true)
    private void mtm$shouldSnow(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (mtm$tooWarm(level, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Unique
    private boolean mtm$tooWarm(LevelReader level, BlockPos pos) {
        ServerLevel server = level instanceof ServerLevel serverLevel ? serverLevel
                : level instanceof WorldGenLevel generating ? generating.getLevel() : null;
        return server != null && MtmSnow.tooWarm(server, (Biome) (Object) this, pos);
    }
}
