package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WaterSurface;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Fills river channels and lake basins of a chunk with water, ahead of the surface rules that dress their beds.
 * The water surface comes from the logic and stays continuous. The blocks around it are interpolated between noise
 * cells and land a little off the logic, so the ground gives way instead: banks too low to hold the water are built
 * up to it, and beds that break the surface are dug out.
 */
public final class MtmWater {
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState BANK = Blocks.STONE.defaultBlockState();
    private static final int SIZE = 16;
    /** The chunk plus one column of its neighbors on each side. */
    private static final int SPAN = SIZE + 2;
    private static final int DRY = WaterSurface.DRY;
    /** Beds rising more than this above the water are left alone: that is land, not a stray block. */
    private static final int MAX_DIG = 4;

    private MtmWater() {
    }

    public static void fill(TerrainLogic logic, ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int[] tops = WaterSurface.sample(startX, startZ, SIZE, logic::sampleWaterLevel);
        boolean any = false;
        for (int top : tops) {
            any |= top != DRY;
        }
        if (!any) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int bottom = chunk.getMinBuildHeight();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                int top = tops[(z + 1) * SPAN + x + 1];
                pos.set(startX + x, 0, startZ + z);
                if (top == DRY) {
                    // The sea needs no banks, and stone on its surface would close off river mouths
                    int required = chunk.getBlockState(pos.setY(ground)).getFluidState().isEmpty() ? highestNeighbor(tops, x + 1, z + 1) : DRY;
                    // Repair noise-cell interpolation only; do not build a stone wall up a waterfall.
                    for (int y = ground + 1; y <= required && required - ground <= MAX_DIG; y++) {
                        chunk.setBlockState(pos.setY(y), BANK, false);
                    }
                    continue;
                }
                if (ground - top > MAX_DIG) {
                    continue;
                }
                for (int y = ground; y > top; y--) {
                    chunk.setBlockState(pos.setY(y), AIR, false);
                }
                for (int y = top; y >= bottom; y--) {
                    pos.setY(y);
                    if (y != top && !chunk.getBlockState(pos).isAir()) {
                        break;
                    }
                    chunk.setBlockState(pos, WATER, false);
                }
            }
        }
    }

    private static int highestNeighbor(int[] tops, int x, int z) {
        int highest = DRY;
        for (int d = 0; d < 4; d++) {
            highest = Math.max(highest, neighbor(tops, x, z, d));
        }
        return highest;
    }

    private static int neighbor(int[] tops, int x, int z, int direction) {
        int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
        int nz = z + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
        return nx < 0 || nz < 0 || nx >= SPAN || nz >= SPAN ? DRY : tops[nz * SPAN + nx];
    }
}
