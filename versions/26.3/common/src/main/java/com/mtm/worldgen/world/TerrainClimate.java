package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.Locale;

/**
 * Vanilla climate parameters derived from MTM terrain, so vanilla biome selection follows the actual terrain:
 * depth decides surface vs. cave biomes, continentalness decides ocean, coast and inland biomes.
 * Learns the world seed the same way as {@link TerrainDensity}.
 */
public final class TerrainClimate implements DensityFunction {
    public enum Kind {
        DEPTH, CONTINENTS;

        String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind byName(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }
    }

    public static final MapCodec<TerrainClimate> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("kind").forGetter(climate -> climate.kind.serializedName()),
            Codec.STRING.fieldOf("settings").forGetter(climate -> climate.settingsJson)
    ).apply(instance, (kind, settings) -> new TerrainClimate(Kind.byName(kind), settings)));

    private static final double DEPTH_LIMIT = 4.0;

    private final Kind kind;
    private final String settingsJson;
    private final WorldgenSettings parsed;
    private final WorldgenSettings.Climate settings;

    private TerrainClimate(Kind kind, String settingsJson) {
        this.kind = kind;
        this.settingsJson = settingsJson;
        this.parsed = WorldgenSettings.fromJson(settingsJson);
        this.settings = parsed.climate;
    }

    /** Placeholder for a router; it learns the seed when vanilla compiles it. */
    public static TerrainClimate unbound(Kind kind, WorldgenSettings settings) {
        return new TerrainClimate(kind, settings.toJson());
    }

    /** The settings of the world this function belongs to. */
    public WorldgenSettings settings() {
        return parsed;
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new Sampler(this, SeedCapture.logic(parsed));
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return Interval.of((float) minValue(), (float) maxValue());
    }

    /**
     * All axes, even for continentalness, which only varies across the map: vanilla slices functions of fewer axes into
     * samplers of its own, and the biome source finds the terrain logic in this one.
     */
    @Override
    public int domainAxes() {
        return ALL_AXES;
    }

    private double compute(TerrainLogic terrain, int x, int y, int z) {
        if (kind == Kind.DEPTH) {
            double depth = (terrain.surfaceHeight(x, z) - y) / settings.depthBlocksPerUnit;
            return Math.max(-DEPTH_LIMIT, Math.min(DEPTH_LIMIT, depth));
        }
        return continentalness(terrain.coastDistance(x, z));
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

    private double minValue() {
        if (kind == Kind.DEPTH) {
            return -DEPTH_LIMIT;
        }
        double min = Double.MAX_VALUE;
        for (double value : settings.continentalness) {
            min = Math.min(min, value);
        }
        return min;
    }

    private double maxValue() {
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
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** The compiled function, which carries the terrain logic of its world. */
    public record Sampler(TerrainClimate function, TerrainLogic logic) implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return (float) function.compute(logic, x, y, z);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, output, volume, this);
        }
    }
}
