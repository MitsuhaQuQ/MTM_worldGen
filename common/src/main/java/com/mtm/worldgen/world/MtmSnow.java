package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Vanilla cools biomes with height on a scale made for 320 block worlds, which would snow far below the MTM snow
 * line. In MTM worlds snow and ice stay away from ground that is neither above the snow line nor in a cold biome.
 */
public final class MtmSnow {
    /** Vanilla biome temperature below which it snows. */
    private static final float SNOW_TEMPERATURE = 0.15F;

    private MtmSnow() {
    }

    /** Whether MTM rules out snow and ice at a position; the vanilla checks still apply where it does not. */
    public static boolean tooWarm(ServerLevel level, Biome biome, BlockPos pos) {
        TerrainLogic logic = MtmGenerators.terrainLogic(level.getChunkSource().getGenerator());
        return logic != null && biome.getBaseTemperature() >= SNOW_TEMPERATURE
                && pos.getY() < logic.sampleSnowLine(pos.getX(), pos.getZ());
    }
}
