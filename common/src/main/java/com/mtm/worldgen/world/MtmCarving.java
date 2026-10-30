package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.core.BlockPos;

/**
 * Keeps caves and ravines below the surface. Carvers carry no seed, so the terrain logic of the chunk being carved is
 * provided per thread by {@link #begin}/{@link #end}, which a mixin calls around NoiseBasedChunkGenerator#applyCarvers.
 * Rivers and lakes are safe with it too: their beds are the surface here, so nothing opens beneath the water.
 */
public final class MtmCarving {
    private static final ThreadLocal<TerrainLogic> CURRENT = new ThreadLocal<>();

    private MtmCarving() {
    }

    public static void begin(TerrainLogic logic) {
        CURRENT.set(logic);
    }

    public static void end() {
        CURRENT.remove();
    }

    /** Whether a position is too close to the surface to carve. Always false outside MTM worlds. */
    public static boolean isProtected(BlockPos pos) {
        TerrainLogic logic = CURRENT.get();
        return logic != null
                && pos.getY() > logic.surfaceHeight(pos.getX(), pos.getZ()) - logic.settings().general.caveSurfaceMargin;
    }
}
