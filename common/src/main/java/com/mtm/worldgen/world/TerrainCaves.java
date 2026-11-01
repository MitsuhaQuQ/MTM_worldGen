package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Vanilla's cave density, kept away from the surface: rock stays solid close below it, so the ground, river beds and
 * lake beds never open up, and caves fade in further down. Bound to the world seed the same way as {@link TerrainDensity}.
 */
public final class TerrainCaves implements DensityFunction {
    public static final Codec<TerrainCaves> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("caves").forGetter(caves -> caves.caves),
            Codec.STRING.fieldOf("settings").forGetter(caves -> caves.settingsJson)
    ).apply(instance, (caves, settings) -> new TerrainCaves(null, caves, settings, WorldgenSettings.fromJson(settings))));
    private static final KeyDispatchDataCodec<TerrainCaves> DISPATCH_CODEC = KeyDispatchDataCodec.of(CODEC);

    /** Blocks over which caves close toward the bottom of the world, like vanilla's bottom slide. */
    private static final double BOTTOM_FADE = 24.0;

    private final DensityFunction caves;
    private final String settingsJson;
    private final WorldgenSettings settings;
    /** Null until the seed is bound; unbound instances only exist as serialized router placeholders. */
    private final TerrainLogic logic;

    private TerrainCaves(TerrainLogic logic, DensityFunction caves, String settingsJson, WorldgenSettings settings) {
        this.logic = logic;
        this.caves = caves;
        this.settingsJson = settingsJson;
        this.settings = settings;
    }

    /** Unbound placeholder for a router; vanilla binds it to the seed when building the generator. */
    public static TerrainCaves unbound(DensityFunction caves, WorldgenSettings settings) {
        return new TerrainCaves(null, caves, settings.toJson(), settings);
    }

    @Override
    public double compute(FunctionContext context) {
        if (logic == null) {
            return 1.0;
        }
        double depth = logic.surfaceHeight(context.blockX(), context.blockZ()) - context.blockY();
        double share = Mth.clamp((depth - settings.general.caveSurfaceMargin) / settings.general.caveFade, 0.0, 1.0);
        share *= Mth.clamp((context.blockY() - settings.minBuildY()) / BOTTOM_FADE, 0.0, 1.0);
        if (share <= 0.0) {
            return 1.0;
        }
        return Mth.clamp(1.0 + (caves.compute(context) - 1.0) * share, -1.0, 1.0);
    }

    @Override
    public void fillArray(double[] densities, ContextProvider provider) {
        provider.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        // Vanilla maps the router again for every chunk, with no seed around: a bound function stays bound
        Long captured = SeedCapture.current();
        TerrainLogic bound = captured != null ? TerrainLogic.forWorld(captured, settings) : logic;
        return visitor.apply(new TerrainCaves(bound, caves.mapAll(visitor), settingsJson, settings));
    }

    @Override
    public double minValue() {
        return -1.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return DISPATCH_CODEC;
    }
}
