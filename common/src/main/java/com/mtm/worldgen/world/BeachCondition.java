package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Surface rule condition: true on block columns the terrain logic marks as beach. Surface rules carry no
 * seed, so the terrain logic of the chunk being surfaced is provided per thread by {@link #begin}/{@link #end},
 * which a mixin calls around NoiseBasedChunkGenerator#buildSurface.
 */
public final class BeachCondition implements SurfaceRules.ConditionSource {
    public static final BeachCondition INSTANCE = new BeachCondition();
    public static final Codec<BeachCondition> CODEC = Codec.unit(INSTANCE);

    private static final ThreadLocal<TerrainLogic> CURRENT = new ThreadLocal<>();

    private BeachCondition() {
    }

    public static void begin(TerrainLogic logic) {
        CURRENT.set(logic);
    }

    public static void end() {
        CURRENT.remove();
    }

    @Override
    public SurfaceRules.Condition apply(SurfaceRules.Context context) {
        TerrainLogic logic = CURRENT.get();
        if (logic == null) {
            return () -> false;
        }
        return () -> logic.isBeach(context.blockX, context.blockZ);
    }

    @Override
    public Codec<? extends SurfaceRules.ConditionSource> codec() {
        return CODEC;
    }
}
