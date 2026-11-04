package com.mtm.worldgen.mixin;

import com.mojang.serialization.Decoder;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.Registry;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Adds the MTM dimension types once the vanilla overworld type they copy has loaded. */
@Mixin(RegistryDataLoader.class)
abstract class RegistryDataLoaderMixin {
    @SuppressWarnings("unchecked")
    @Inject(method = "loadRegistryContents", at = @At("TAIL"))
    private static <E> void mtm$addDimensionTypes(RegistryOps.RegistryInfoLookup lookup, ResourceManager resources,
                                                  ResourceKey<? extends Registry<E>> key, WritableRegistry<E> registry,
                                                  Decoder<E> decoder, Map<ResourceKey<?>, Exception> errors, CallbackInfo ci) {
        if (key.equals(Registries.DIMENSION_TYPE)) {
            MtmGenerators.registerDimensionTypes((WritableRegistry<DimensionType>) registry);
        }
    }
}
