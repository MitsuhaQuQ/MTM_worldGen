package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * World presets are static data, but the MTM generator and build height come from the config file. The Create World
 * screen and server.properties both build worlds through this method, so the config is read here.
 */
@Mixin(WorldPreset.class)
abstract class WorldPresetMixin {
    @Inject(method = "createWorldDimensions", at = @At("RETURN"), cancellable = true)
    private void mtm$applyConfig(CallbackInfoReturnable<WorldDimensions> cir) {
        cir.setReturnValue(MtmGenerators.applyConfig(cir.getReturnValue()));
    }
}
