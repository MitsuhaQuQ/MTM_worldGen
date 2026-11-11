package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.OreHeights;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.levelgen.Heightmap;
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

    /** How far above the ground an ore attempt may start and still reach it: farther than any ore blob spreads. */
    private static final int ABOVE_GROUND = 16;

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
        // Attempts well above the ground there could only reach air: in a world much taller than its land, most of the
        // stretched range lies above it, and skipping those attempts saves the feature stage the work
        int top = context.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, origin.getX(), origin.getZ()) + ABOVE_GROUND;
        return Arrays.stream(OreHeights.stretch(y, factor, random::nextDouble)).filter(h -> h <= top).mapToObj(origin::atY);
    }
}
