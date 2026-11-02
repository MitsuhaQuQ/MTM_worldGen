package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Turns off vanilla's blending toward old chunks in MTM worlds, which do that themselves and far more gently. */
@Mixin(Blender.class)
abstract class BlenderMixin {
    @Inject(method = "of", at = @At("HEAD"), cancellable = true)
    private static void mtm$noBlending(WorldGenRegion region, CallbackInfoReturnable<Blender> cir) {
        if (region != null && MtmGenerators.terrainLogic(region.getLevel()) != null) {
            cir.setReturnValue(Blender.empty());
        }
    }
}
