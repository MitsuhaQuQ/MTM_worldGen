package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.SeedCapture;
import com.mtm.worldgen.world.TerrainLogicHolder;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives the seed to MTM density functions, which vanilla compiles lazily when a sampler is first asked for, and keeps
 * the terrain logic of the state's world.
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin implements TerrainLogicHolder {
    @Shadow
    @Final
    private long seed;

    @Shadow
    @Final
    private NoiseRouter router;

    @Unique
    private TerrainLogic mtm_worldgen$logic;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void mtm_worldgen$findLogic(CallbackInfo ci) {
        mtm_worldgen$logic = MtmGenerators.findTerrainLogic(router, seed);
    }

    @Override
    public TerrainLogic mtm$terrainLogic() {
        return mtm_worldgen$logic;
    }

    @Inject(method = "getSampler", at = @At("HEAD"))
    private void mtm_worldgen$beginSeed(DensityFunction function, CallbackInfoReturnable<DensitySampler> cir) {
        SeedCapture.begin(seed);
    }

    @Inject(method = "getSampler", at = @At("RETURN"))
    private void mtm_worldgen$endSeed(DensityFunction function, CallbackInfoReturnable<DensitySampler> cir) {
        SeedCapture.end();
    }
}
