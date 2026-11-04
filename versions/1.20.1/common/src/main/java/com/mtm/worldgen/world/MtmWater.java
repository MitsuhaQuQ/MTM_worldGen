package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

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
    private static final int DRY = Integer.MIN_VALUE;
    /** Enough rounds for a step in the water surface to spread across a chunk. */
    private static final int ROUNDS = 8;
    /** Beds rising more than this above the water are left alone: that is land, not a stray block. */
    private static final int MAX_DIG = 4;

    private MtmWater() {
    }

    public static void fill(TerrainLogic logic, ChunkAccess chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int[] tops = new int[SPAN * SPAN];
        boolean any = false;
        for (int z = 0; z < SPAN; z++) {
            for (int x = 0; x < SPAN; x++) {
                double level = logic.sampleWaterLevel(startX + x - 1, startZ + z - 1);
                tops[z * SPAN + x] = Double.isNaN(level) ? DRY : (int) Math.floor(level);
                any |= !Double.isNaN(level);
            }
        }
        if (!any) {
            return;
        }
        clean(tops);
        smooth(tops);
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
                    for (int y = ground + 1; y <= required; y++) {
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

    /**
     * Rounds the outline of the water: spikes one column wide go dry and notches one column wide fill up, because
     * banks follow the outline block by block and would turn every such column into a sharp corner.
     */
    private static void clean(int[] tops) {
        int[] source = Arrays.copyOf(tops, tops.length);
        for (int z = 1; z < SPAN - 1; z++) {
            for (int x = 1; x < SPAN - 1; x++) {
                int wet = 0;
                int highest = DRY;
                for (int d = 0; d < 4; d++) {
                    int value = neighbor(source, x, z, d);
                    if (value != DRY) {
                        wet++;
                        highest = Math.max(highest, value);
                    }
                }
                int index = z * SPAN + x;
                if (source[index] != DRY && wet <= 1) {
                    tops[index] = DRY;
                } else if (source[index] == DRY && wet >= 3) {
                    tops[index] = highest;
                }
            }
        }
    }

    /** Water falls at most one block between neighbors, so a surface never ends in a cliff of source blocks. */
    private static void smooth(int[] tops) {
        int[] previous = new int[tops.length];
        for (int round = 0; round < ROUNDS && !Arrays.equals(previous, tops); round++) {
            System.arraycopy(tops, 0, previous, 0, tops.length);
            for (int z = 0; z < SPAN; z++) {
                for (int x = 0; x < SPAN; x++) {
                    int index = z * SPAN + x;
                    if (tops[index] == DRY) {
                        continue;
                    }
                    int lowest = lowestNeighbor(tops, x, z);
                    if (lowest != DRY && tops[index] > lowest + 1) {
                        tops[index] = lowest + 1;
                    }
                }
            }
        }
    }

    private static int lowestNeighbor(int[] tops, int x, int z) {
        int lowest = DRY;
        for (int d = 0; d < 4; d++) {
            int value = neighbor(tops, x, z, d);
            if (value != DRY && (lowest == DRY || value < lowest)) {
                lowest = value;
            }
        }
        return lowest;
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
