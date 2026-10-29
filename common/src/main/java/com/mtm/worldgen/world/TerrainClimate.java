package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * Vanilla climate parameters derived from MTM terrain, so vanilla biome selection follows the actual terrain:
 * depth decides surface vs. cave biomes, continentalness decides ocean, coast and inland biomes.
 * Bound to the world seed the same way as {@link TerrainDensity}.
 */
public final class TerrainClimate implements DensityFunction.SimpleFunction {
    public enum Kind {
        DEPTH, CONTINENTS;

        String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byName(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }
    }

    public static final TerrainClimate DEPTH = new TerrainClimate(null, Kind.DEPTH);
    public static final TerrainClimate CONTINENTS = new TerrainClimate(null, Kind.CONTINENTS);
    public static final Codec<TerrainClimate> CODEC = Codec.STRING.fieldOf("kind")
            .xmap(name -> Kind.byName(name) == Kind.DEPTH ? DEPTH : CONTINENTS, climate -> climate.kind.serializedName())
            .codec();

    /** Vanilla depth grows by 1.5 over 192 blocks of y: about 1 per 128 blocks below the surface. */
    private static final double DEPTH_BLOCKS_PER_UNIT = 128.0;
    private static final double DEPTH_LIMIT = 4.0;

    /**
     * Coast distance in blocks (negative offshore) to vanilla continentalness, piecewise linear through the
     * vanilla category boundaries: deep ocean below -0.455, ocean to -0.19, coast to -0.11, near inland to 0.03,
     * mid inland to 0.3, far inland above. Kept above -1.05, where mushroom fields would start.
     */
    private static final double[] COAST_DISTANCES = {-4096.0, -1024.0, 0.0, 64.0, 512.0, 2048.0, 4096.0};
    private static final double[] CONTINENTALNESS = {-1.0, -0.455, -0.19, -0.11, 0.03, 0.3, 0.8};

    private final Kind kind;
    /** Null until the seed is bound; unbound instances only exist as serialized router placeholders. */
    private final TerrainLogic logic;

    private TerrainClimate(Long seed, Kind kind) {
        this.kind = kind;
        this.logic = seed != null ? TerrainLogic.forSeed(seed) : null;
    }

    @Override
    public double compute(FunctionContext context) {
        TerrainLogic terrain = logic != null ? logic : TerrainLogic.forSeed(0L);
        if (kind == Kind.DEPTH) {
            double depth = (terrain.surfaceHeight(context.blockX(), context.blockZ()) - context.blockY()) / DEPTH_BLOCKS_PER_UNIT;
            return Math.max(-DEPTH_LIMIT, Math.min(DEPTH_LIMIT, depth));
        }
        return continentalness(terrain.coastDistance(context.blockX(), context.blockZ()));
    }

    static double continentalness(double coastDistance) {
        if (coastDistance <= COAST_DISTANCES[0]) {
            return CONTINENTALNESS[0];
        }
        for (int i = 1; i < COAST_DISTANCES.length; i++) {
            if (coastDistance <= COAST_DISTANCES[i]) {
                double t = (coastDistance - COAST_DISTANCES[i - 1]) / (COAST_DISTANCES[i] - COAST_DISTANCES[i - 1]);
                return CONTINENTALNESS[i - 1] + t * (CONTINENTALNESS[i] - CONTINENTALNESS[i - 1]);
            }
        }
        return CONTINENTALNESS[CONTINENTALNESS.length - 1];
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        Long captured = SeedCapture.current();
        return visitor.apply(captured != null ? new TerrainClimate(captured, kind) : this);
    }

    @Override
    public double minValue() {
        return kind == Kind.DEPTH ? -DEPTH_LIMIT : CONTINENTALNESS[0];
    }

    @Override
    public double maxValue() {
        return kind == Kind.DEPTH ? DEPTH_LIMIT : CONTINENTALNESS[CONTINENTALNESS.length - 1];
    }

    @Override
    public Codec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
