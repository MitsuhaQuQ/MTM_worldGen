package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.ResourceManagerRegistryLoadTask;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.stream.Stream;

/**
 * Adds the MTM dimension types once the vanilla overworld type they copy has loaded from data packs. Registries a
 * client receives from a server already hold them, so only loading from resources adds them.
 */
@Mixin(RegistryLoadTask.class)
abstract class RegistryLoadTaskMixin<T> {
    @Shadow
    @Final
    private WritableRegistry<T> registry;

    @SuppressWarnings("unchecked")
    @Inject(method = "registerElements", at = @At("TAIL"))
    private void mtm$addDimensionTypes(Stream<?> elements, CallbackInfo ci) {
        if ((Object) this instanceof ResourceManagerRegistryLoadTask<?> && registry.key().equals(Registries.DIMENSION_TYPE)) {
            MtmGenerators.registerDimensionTypes((WritableRegistry<DimensionType>) registry);
        }
    }
}
