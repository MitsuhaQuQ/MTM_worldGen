package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Terrain density: positive below the surface, negative above. Serialized without the seed;
 * the seed is bound when vanilla maps the router during chunk generator construction.
 */
public final class TerrainDensity implements DensityFunction.SimpleFunction {
    public static final Codec<TerrainDensity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("initial", false).forGetter(density -> density.initial),
            Codec.STRING.fieldOf("settings").forGetter(density -> density.settingsJson)
    ).apply(instance, (initial, settings) -> new TerrainDensity(null, initial, settings)));

    private static final double FALLOFF = 8.0;
    /**
     * NoiseRouterData#computePreliminarySurfaceLevelScanning treats a cell as solid when
     * density - 0.703125 > 0.390625, so the initial density crosses 1.09375 at the preliminary surface.
     */
    private static final double PRELIMINARY_SURFACE_THRESHOLD = 1.09375;
    /**
     * How far below the real surface the preliminary surface sits, as in vanilla. The aquifer adds 8 blocks to
     * the preliminary surface and floods with sea water only when that point is below sea level; with the
     * preliminary surface at the real surface, land slightly below sea level counted as underground and stayed
     * dry next to vertical walls of sea water.
     */
    private static final double PRELIMINARY_SURFACE_DEPTH = 8.0;
    private static final double INITIAL_LIMIT = 64.0;

    private final boolean initial;
    /** The world's locked settings; serialized with the function, so they live in level.dat. */
    private final String settingsJson;
    /** Null until the seed is bound; unbound instances only exist as serialized router placeholders. */
    private final TerrainLogic logic;

    /**
     * @param seed         world seed, or null for the unbound placeholder
     * @param initial      true for the router's initialDensityWithoutJaggedness slot, which vanilla scans to find the
     *                     preliminary surface level used by surface rules (grass, dirt, sand)
     * @param settingsJson the world's settings
     */
    private TerrainDensity(Long seed, boolean initial, String settingsJson) {
        this.initial = initial;
        this.settingsJson = settingsJson;
        this.logic = seed != null ? TerrainLogic.forWorld(seed, WorldgenSettings.fromJson(settingsJson)) : null;
    }

    /** Unbound placeholder for a router; vanilla binds it to the seed when building the generator. */
    public static TerrainDensity unbound(boolean initial, WorldgenSettings settings) {
        return new TerrainDensity(null, initial, settings.toJson());
    }

    @Override
    public double compute(FunctionContext context) {
        TerrainLogic terrain = logic != null ? logic : TerrainLogic.forWorld(0L, WorldgenSettings.fromJson(settingsJson));
        double depth = terrain.surfaceHeight(context.blockX(), context.blockZ()) - context.blockY();
        if (initial) {
            return Mth.clamp(PRELIMINARY_SURFACE_THRESHOLD + (depth - PRELIMINARY_SURFACE_DEPTH) / FALLOFF,
                    -INITIAL_LIMIT, INITIAL_LIMIT);
        }
        return Mth.clamp(depth / FALLOFF, -1.0, 1.0);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        Long captured = SeedCapture.current();
        return visitor.apply(captured != null ? new TerrainDensity(captured, initial, settingsJson) : this);
    }

    @Override
    public double minValue() {
        return initial ? -INITIAL_LIMIT : -1.0;
    }

    @Override
    public double maxValue() {
        return initial ? INITIAL_LIMIT : 1.0;
    }

    @Override
    public Codec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
