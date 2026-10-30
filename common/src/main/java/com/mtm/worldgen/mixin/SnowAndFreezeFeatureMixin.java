package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmSnow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Applies the MTM snow line to the snow and ice placed during world generation. */
@Mixin(SnowAndFreezeFeature.class)
abstract class SnowAndFreezeFeatureMixin {
    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean mtm$shouldFreeze(Biome biome, LevelReader level, BlockPos pos, boolean mustBeAtEdge) {
        return !MtmSnow.tooWarm(((WorldGenLevel) level).getLevel(), biome, pos) && biome.shouldFreeze(level, pos, mustBeAtEdge);
    }

    @Redirect(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean mtm$shouldSnow(Biome biome, LevelReader level, BlockPos pos) {
        return !MtmSnow.tooWarm(((WorldGenLevel) level).getLevel(), biome, pos) && biome.shouldSnow(level, pos);
    }
}
