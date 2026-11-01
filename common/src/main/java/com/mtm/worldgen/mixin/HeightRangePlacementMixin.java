package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmOres;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.stream.Stream;

/** Spreads ores over the taller rock of MTM worlds. */
@Mixin(HeightRangePlacement.class)
abstract class HeightRangePlacementMixin {
    @Shadow
    @Final
    private HeightProvider height;

    @Inject(method = "getPositions", at = @At("HEAD"), cancellable = true)
    private void mtm$stretchOres(PlacementContext context, RandomSource random, BlockPos pos,
                                 CallbackInfoReturnable<Stream<BlockPos>> cir) {
        if (MtmOres.isOre(context)) {
            TerrainLogic logic = MtmGenerators.terrainLogic(context.getLevel().getLevel());
            if (logic != null) {
                cir.setReturnValue(MtmOres.positions(height, context, random, pos, logic.settings().oreHeightScale()));
            }
        }
    }
}
