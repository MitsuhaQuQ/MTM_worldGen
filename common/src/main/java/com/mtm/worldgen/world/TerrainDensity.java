package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Terrain density: positive below the surface, negative above. Serialized without the seed;
 * the seed is bound when vanilla maps the router during chunk generator construction.
 */
public final class TerrainDensity implements DensityFunction.SimpleFunction {
    public static final TerrainDensity FINAL = new TerrainDensity(null, false);
    public static final TerrainDensity INITIAL = new TerrainDensity(null, true);
    public static final Codec<TerrainDensity> CODEC = Codec.BOOL.optionalFieldOf("initial", false)
            .xmap(initial -> initial ? INITIAL : FINAL, TerrainDensity::initial)
            .codec();

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
    /** Null until the seed is bound; unbound instances only exist as serialized router placeholders. */
    private final TerrainLogic logic;

    /**
     * @param seed    world seed, or null for the unbound placeholder
     * @param initial true for the router's initialDensityWithoutJaggedness slot, which vanilla scans to find the
     *                preliminary surface level used by surface rules (grass, dirt, sand)
     */
    private TerrainDensity(Long seed, boolean initial) {
        this.initial = initial;
        this.logic = seed != null ? TerrainLogic.forSeed(seed) : null;
    }

    public boolean initial() {
        return initial;
    }

    /** The terrain logic this function samples, or null for the unbound placeholder. */
    public TerrainLogic logic() {
        return logic;
    }

    @Override
    public double compute(FunctionContext context) {
        TerrainLogic terrain = logic != null ? logic : TerrainLogic.forSeed(0L);
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
        return visitor.apply(captured != null ? new TerrainDensity(captured, initial) : this);
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
