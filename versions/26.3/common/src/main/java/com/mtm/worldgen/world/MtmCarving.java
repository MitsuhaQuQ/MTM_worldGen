package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;

/**
 * Keeps caves and ravines below the surface. Carvers carry no seed, so the terrain logic of the chunk being carved is
 * provided per thread by {@link #begin}/{@link #end}, which a mixin calls around NoiseBasedChunkGenerator#generateCarvers.
 * Rivers and lakes are safe with it too: their beds are the surface here, so nothing opens beneath the water.
 */
public final class MtmCarving {
    private record Carving(TerrainLogic logic, ChunkPos chunk) {
    }

    private static final ThreadLocal<Carving> CURRENT = new ThreadLocal<>();

    private MtmCarving() {
    }

    public static void begin(TerrainLogic logic, ChunkPos chunk) {
        CURRENT.set(new Carving(logic, chunk));
    }

    public static void end() {
        CURRENT.remove();
    }

    /**
     * The filter of a carving mask with the surface protected: blocks too close to it stay. Unchanged outside MTM
     * worlds. The filter takes positions within the chunk.
     */
    public static CarvingMask.Filter protect(CarvingMask.Filter filter) {
        Carving carving = CURRENT.get();
        if (carving == null) {
            return filter;
        }
        int startX = carving.chunk().getMinBlockX();
        int startZ = carving.chunk().getMinBlockZ();
        double margin = carving.logic().settings().general.caveSurfaceMargin;
        return (x, y, z) -> (filter == null || filter.test(x, y, z))
                && y <= carving.logic().surfaceHeight(startX + x, startZ + z) - margin;
    }
}
