package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.SeedCapture;
import net.minecraft.core.HolderGetter;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RandomState.class)
public abstract class RandomStateMixin {
    private static final String CREATE = "create(Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;"
            + "Lnet/minecraft/core/HolderGetter;J)Lnet/minecraft/world/level/levelgen/RandomState;";

    @Inject(method = CREATE, at = @At("HEAD"))
    private static void mtm_worldgen$beginSeed(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises,
                                               long seed, CallbackInfoReturnable<RandomState> cir) {
        SeedCapture.begin(seed);
    }

    @Inject(method = CREATE, at = @At("RETURN"))
    private static void mtm_worldgen$endSeed(NoiseGeneratorSettings settings, HolderGetter<NormalNoise.NoiseParameters> noises,
                                             long seed, CallbackInfoReturnable<RandomState> cir) {
        SeedCapture.end();
    }
}
