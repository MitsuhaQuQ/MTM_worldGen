package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.ForeignChunks;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prepares an MTM overworld when it is created: its cache folder, and the foreign chunks merged into it. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void mtm$fitForeignChunks(CallbackInfo ci) {
        MtmGenerators.useCacheDirectory((ServerLevel) (Object) this);
        ForeignChunks.prepare((ServerLevel) (Object) this);
    }
}
