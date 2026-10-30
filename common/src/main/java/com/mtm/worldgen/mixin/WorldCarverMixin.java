package com.mtm.worldgen.mixin;

import com.mtm.worldgen.world.MtmCarving;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.carver.CarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Function;

/** Every block a cave or ravine removes passes through here, which makes it the one place to protect the surface. */
@Mixin(WorldCarver.class)
abstract class WorldCarverMixin {
    @Inject(method = "carveBlock", at = @At("HEAD"), cancellable = true)
    private void mtm$protectSurface(CarvingContext context, CarverConfiguration config, ChunkAccess chunk,
                                    Function<BlockPos, Holder<Biome>> biomes, CarvingMask mask,
                                    BlockPos.MutableBlockPos pos, BlockPos.MutableBlockPos checkPos, Aquifer aquifer,
                                    MutableBoolean reachedSurface, CallbackInfoReturnable<Boolean> cir) {
        if (MtmCarving.isProtected(pos)) {
            cir.setReturnValue(false);
        }
    }
}
