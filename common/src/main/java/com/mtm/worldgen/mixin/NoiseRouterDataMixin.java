package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.SeedCapture;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseRouterWithOnlyNoises;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseRouterData.class)
public abstract class NoiseRouterDataMixin {
    @Inject(method = "createNoiseRouter", at = @At("HEAD"))
    private static void mtm_worldgen$beginSeed(NoiseSettings settings, long seed, Registry<NormalNoise.NoiseParameters> noises,
                                               WorldgenRandom.Algorithm algorithm, NoiseRouterWithOnlyNoises router,
                                               CallbackInfoReturnable<NoiseRouter> cir) {
        SeedCapture.begin(seed);
    }

    @Inject(method = "createNoiseRouter", at = @At("RETURN"))
    private static void mtm_worldgen$endSeed(NoiseSettings settings, long seed, Registry<NormalNoise.NoiseParameters> noises,
                                             WorldgenRandom.Algorithm algorithm, NoiseRouterWithOnlyNoises router,
                                             CallbackInfoReturnable<NoiseRouter> cir) {
        SeedCapture.end();
    }
}
