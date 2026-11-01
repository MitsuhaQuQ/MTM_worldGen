package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;

import java.util.Locale;

/**
 * Surface rule condition answered by the terrain logic: beach, snow and bare rock columns. Surface rules carry no
 * seed, so the terrain logic of the chunk being surfaced is provided per thread by {@link #begin}/{@link #end},
 * which a mixin calls around NoiseBasedChunkGenerator#buildSurface.
 */
public record TerrainCondition(Kind kind) implements SurfaceRules.ConditionSource {
    public enum Kind {
        BEACH, GRAVEL_SHORE, ROCK_SHORE, SNOW, ROCK, ROCK_PATCH, STRATUM_DARK, STRATUM_TUFF, STRATUM_PALE, STRATUM_SAND;

        boolean test(TerrainLogic logic, int x, int y, int z) {
            return switch (this) {
                case BEACH -> logic.isBeach(x, z);
                case GRAVEL_SHORE -> logic.isGravelShore(x, z);
                case ROCK_SHORE -> logic.isRockShore(x, z);
                case SNOW -> logic.isSnow(x, z);
                case ROCK -> logic.isRock(x, z);
                case ROCK_PATCH -> logic.isRockPatch(x, z);
                case STRATUM_DARK -> logic.rockStratum(x, y, z) == 1;
                case STRATUM_TUFF -> logic.rockStratum(x, y, z) == 2;
                case STRATUM_PALE -> logic.rockStratum(x, y, z) == 3;
                case STRATUM_SAND -> logic.rockStratum(x, y, z) == 4;
            };
        }
    }

    public static final Codec<TerrainCondition> CODEC = Codec.STRING.fieldOf("kind").xmap(
            name -> new TerrainCondition(Kind.valueOf(name.toUpperCase(Locale.ROOT))),
            condition -> condition.kind.name().toLowerCase(Locale.ROOT)).codec();
    private static final KeyDispatchDataCodec<TerrainCondition> DISPATCH_CODEC = KeyDispatchDataCodec.of(CODEC);

    private static final ThreadLocal<TerrainLogic> CURRENT = new ThreadLocal<>();

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
        return () -> kind.test(logic, context.blockX, context.blockY, context.blockZ);
    }

    @Override
    public KeyDispatchDataCodec<? extends SurfaceRules.ConditionSource> codec() {
        return DISPATCH_CODEC;
    }
}
