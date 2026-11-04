package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla calls any overworld with a custom dimension type experimental and warns on every world load. */
@Mixin(WorldDimensions.class)
abstract class WorldDimensionsMixin {
    @Inject(method = "isStableOverworld", at = @At("HEAD"), cancellable = true)
    private static void mtm$stableOverworld(LevelStem stem, CallbackInfoReturnable<Boolean> cir) {
        if (stem.generator().getBiomeSource() instanceof MtmBiomeSource) {
            cir.setReturnValue(true);
        }
    }
}
