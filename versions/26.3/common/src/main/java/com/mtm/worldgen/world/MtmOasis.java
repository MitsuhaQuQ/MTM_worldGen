package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.noise.Hashing;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Sugar cane along the water of oases, after the surface rules have laid their grass. The biome brings grass and a
 * few trees on its own, but its scattered cane patches mostly miss a pond this small. Each stalk comes from the seed
 * and its column alone.
 */
public final class MtmOasis {
    private static final BlockState CANE = Blocks.SUGAR_CANE.defaultBlockState();
    private static final int SIZE = 16;
    private static final long CANE_SALT = 0x43414E45L;

    private MtmOasis() {
    }

    public static void plant(TerrainLogic logic, ChunkAccess chunk) {
        if (!logic.settings().deserts.oases) {
            return;
        }
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        if (!anyOasis(logic, startX, startZ)) {
            return;
        }
        long caneSeed = Hashing.derive(logic.worldId(), CANE_SALT);
        double chance = logic.settings().deserts.caneChance;
        int tallest = logic.settings().deserts.caneHeight;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int worldX = startX + x;
                int worldZ = startZ + z;
                long hash = Hashing.hash(caneSeed, worldX, worldZ);
                if (Hashing.unit(hash) >= chance || !logic.isOasis(worldX, worldZ)) {
                    continue;
                }
                int ground = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
                BlockState soil = chunk.getBlockState(pos.set(worldX, ground, worldZ));
                if (!soil.is(Blocks.GRASS_BLOCK) && !soil.is(Blocks.DIRT) && !soil.is(Blocks.SAND)
                        || !chunk.getBlockState(pos.setY(ground + 1)).isAir() || !besideWater(chunk, side, worldX, ground, worldZ, x, z)) {
                    continue;
                }
                int height = 1 + (int) (Hashing.unit(Hashing.mix(hash)) * tallest);
                for (int y = 1; y <= Math.min(height, tallest); y++) {
                    chunk.setBlockState(pos.setY(ground + y), CANE);
                }
            }
        }
    }

    /**
     * Whether any column of a chunk, on a coarse grid that takes in its edges, lies on the green land of an oasis. That
     * land is wider than a chunk, so it cannot slip between the samples.
     */
    private static boolean anyOasis(TerrainLogic logic, int startX, int startZ) {
        for (int z = 0; z < SIZE; z += 5) {
            for (int x = 0; x < SIZE; x += 5) {
                if (logic.isOasis(startX + x, startZ + z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether water lies next to a block on one of its sides within the chunk, as sugar cane needs. */
    private static boolean besideWater(ChunkAccess chunk, BlockPos.MutableBlockPos side, int worldX, int y, int worldZ, int x, int z) {
        for (int d = 0; d < 4; d++) {
            int dx = d == 0 ? -1 : d == 1 ? 1 : 0;
            int dz = d == 2 ? -1 : d == 3 ? 1 : 0;
            if (x + dx < 0 || z + dz < 0 || x + dx >= SIZE || z + dz >= SIZE) {
                continue;
            }
            if (chunk.getBlockState(side.set(worldX + dx, y, worldZ + dz)).is(Blocks.WATER)) {
                return true;
            }
        }
        return false;
    }
}
