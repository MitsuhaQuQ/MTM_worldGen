package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
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

    public static final Codec<TerrainClimate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("kind").forGetter(climate -> climate.kind.serializedName()),
            Codec.STRING.fieldOf("settings").forGetter(climate -> climate.settingsJson)
    ).apply(instance, (kind, settings) -> new TerrainClimate(null, Kind.byName(kind), settings)));

    private static final double DEPTH_LIMIT = 4.0;

    private final Kind kind;
    private final String settingsJson;
    private final WorldgenSettings.Climate settings;
    /** Null until the seed is bound; unbound instances only exist as serialized router placeholders. */
    private final TerrainLogic logic;

    private TerrainClimate(Long seed, Kind kind, String settingsJson) {
        WorldgenSettings parsed = WorldgenSettings.fromJson(settingsJson);
        this.kind = kind;
        this.settingsJson = settingsJson;
        this.settings = parsed.climate;
        this.logic = seed != null ? TerrainLogic.forWorld(seed, parsed) : null;
    }

    /** Unbound placeholder for a router; vanilla binds it to the seed when building the generator. */
    public static TerrainClimate unbound(Kind kind, WorldgenSettings settings) {
        return new TerrainClimate(null, kind, settings.toJson());
    }

    /** The terrain logic this function samples, or null for the unbound placeholder. */
    public TerrainLogic logic() {
        return logic;
    }

    @Override
    public double compute(FunctionContext context) {
        TerrainLogic terrain = logic != null ? logic : TerrainLogic.forWorld(0L, WorldgenSettings.fromJson(settingsJson));
        if (kind == Kind.DEPTH) {
            double depth = (terrain.surfaceHeight(context.blockX(), context.blockZ()) - context.blockY())
                    / settings.depthBlocksPerUnit;
            return Math.max(-DEPTH_LIMIT, Math.min(DEPTH_LIMIT, depth));
        }
        return continentalness(terrain.coastDistance(context.blockX(), context.blockZ()));
    }

    /** Coast distance in blocks (negative offshore) to vanilla continentalness, piecewise linear. */
    private double continentalness(double coastDistance) {
        double[] distances = settings.coastDistances;
        double[] values = settings.continentalness;
        if (coastDistance <= distances[0]) {
            return values[0];
        }
        for (int i = 1; i < distances.length; i++) {
            if (coastDistance <= distances[i]) {
                double t = (coastDistance - distances[i - 1]) / (distances[i] - distances[i - 1]);
                return values[i - 1] + t * (values[i] - values[i - 1]);
            }
        }
        return values[values.length - 1];
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        Long captured = SeedCapture.current();
        return visitor.apply(captured != null ? new TerrainClimate(captured, kind, settingsJson) : this);
    }

    @Override
    public double minValue() {
        if (kind == Kind.DEPTH) {
            return -DEPTH_LIMIT;
        }
        double min = Double.MAX_VALUE;
        for (double value : settings.continentalness) {
            min = Math.min(min, value);
        }
        return min;
    }

    @Override
    public double maxValue() {
        if (kind == Kind.DEPTH) {
            return DEPTH_LIMIT;
        }
        double max = -Double.MAX_VALUE;
        for (double value : settings.continentalness) {
            max = Math.max(max, value);
        }
        return max;
    }

    @Override
    public Codec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
