package com.mtm.worldgen.mixin;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.MtmOres;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/** Spreads ores over the taller rock of MTM worlds, or leaves them out of worlds without ores. */
@Mixin(HeightRangePlacement.class)
abstract class HeightRangePlacementMixin {
    @Shadow
    public abstract HeightProvider height();

    @Inject(method = "modify", at = @At("HEAD"), cancellable = true)
    private void mtm$stretchOres(PlacementContext context, RandomSource random, BlockPos pos, Consumer<BlockPos> output,
                                 CallbackInfo ci) {
        if (MtmOres.isOre(context)) {
            TerrainLogic logic = MtmGenerators.terrainLogic(context.getLevel().getLevel());
            if (logic != null) {
                WorldgenSettings.General general = logic.settings().general;
                if (general.ores && (general.undergroundFallingBlocks || !MtmOres.placesFallingBlocks(context))) {
                    MtmOres.positions(height(), context, random, pos, logic.settings().oreHeightScale()).forEach(output);
                }
                ci.cancel();
            }
        }
    }
}
