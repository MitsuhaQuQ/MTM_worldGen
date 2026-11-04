package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.OreHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

import java.util.Arrays;
import java.util.stream.Stream;

/** Ore placement in MTM worlds: vanilla's height ranges, stretched above 0 by the height of the world. */
public final class MtmOres {
    /** Heights are sampled as in a vanilla world, or ranges up to the top of the world would start out stretched. */
    private static final LevelHeightAccessor VANILLA_HEIGHT = LevelHeightAccessor.create(-64, 384);

    private MtmOres() {
    }

    /** Whether a placement belongs to an ore. */
    public static boolean isOre(PlacementContext context) {
        return context.topFeature().map(placed -> placed.feature().value() instanceof AbstractOreFeature).orElse(false);
    }

    /** Whether a placement belongs to an ore that places blocks that fall, such as gravel. */
    public static boolean placesFallingBlocks(PlacementContext context) {
        return context.topFeature().map(MtmOres::placesFallingBlocks).orElse(false);
    }

    public static boolean placesFallingBlocks(PlacedFeature placed) {
        if (!(placed.feature().value() instanceof AbstractOreFeature ore)) {
            return false;
        }
        for (BlockReplacement target : ore.targetStates()) {
            if (target.state().getBlock() instanceof FallingBlock) {
                return true;
            }
        }
        return false;
    }

    public static Stream<BlockPos> positions(HeightProvider height, PlacementContext context, RandomSource random,
                                             BlockPos origin, double factor) {
        int y = height.sample(random, new WorldGenerationContext(context.generator(), VANILLA_HEIGHT));
        return Arrays.stream(OreHeights.stretch(y, factor, random::nextDouble)).mapToObj(origin::atY);
    }
}
