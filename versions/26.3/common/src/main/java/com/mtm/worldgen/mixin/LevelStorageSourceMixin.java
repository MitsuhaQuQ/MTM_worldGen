package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builds the MTM generator of a world again from its locked settings as the world generation settings are read, so
 * that worlds made by another Minecraft version open with their own terrain (see
 * {@link MtmGenerators#rebuildStoredGenerator}). The registries come from the method's arguments, and the data from the
 * call that parses it.
 */
@Mixin(LevelStorageSource.class)
abstract class LevelStorageSourceMixin {
    @Unique
    private static final ThreadLocal<HolderLookup.Provider> MTM$REGISTRIES = new ThreadLocal<>();

    @Inject(method = "readExistingSavedData", at = @At("HEAD"))
    private static void mtm$rememberRegistries(LevelStorageSource.LevelStorageAccess access, HolderLookup.Provider registries,
                                               SavedDataType<?> type, CallbackInfoReturnable<?> cir) {
        MTM$REGISTRIES.set(type == WorldGenSettings.TYPE ? registries : null);
    }

    @ModifyArg(method = "readExistingSavedData", index = 1, at = @At(value = "INVOKE",
            target = "Lcom/mojang/serialization/Codec;parse(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"))
    private static Object mtm$rebuildGenerator(Object data) {
        HolderLookup.Provider registries = MTM$REGISTRIES.get();
        MTM$REGISTRIES.remove();
        return registries != null && data instanceof Tag tag ? MtmGenerators.rebuildStoredGenerator(tag, registries) : data;
    }
}
