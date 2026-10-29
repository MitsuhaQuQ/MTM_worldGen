package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.terrain.PrototypeHeight;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Terrain density: positive below the surface, negative above. Serialized without the seed;
 * the seed is bound when vanilla maps the router during chunk generator construction.
 *
 * @param initial true for the router's initialDensityWithoutJaggedness slot, which vanilla scans to find the
 *                preliminary surface level used by surface rules (grass, dirt, sand)
 */
public record TerrainDensity(long seed, boolean initial) implements DensityFunction.SimpleFunction {
    public static final TerrainDensity FINAL = new TerrainDensity(0L, false);
    public static final TerrainDensity INITIAL = new TerrainDensity(0L, true);
    public static final Codec<TerrainDensity> CODEC = Codec.BOOL.optionalFieldOf("initial", false)
            .xmap(initial -> initial ? INITIAL : FINAL, TerrainDensity::initial)
            .codec();

    private static final double FALLOFF = 8.0;
    /**
     * NoiseRouterData#computePreliminarySurfaceLevelScanning treats a cell as solid when
     * density - 0.703125 > 0.390625, so the initial density must cross 1.09375 at the surface.
     */
    private static final double PRELIMINARY_SURFACE_THRESHOLD = 1.09375;
    private static final double INITIAL_LIMIT = 64.0;

    @Override
    public double compute(FunctionContext context) {
        double depth = PrototypeHeight.height(seed, context.blockX(), context.blockZ()) - context.blockY();
        if (initial) {
            return Mth.clamp(PRELIMINARY_SURFACE_THRESHOLD + depth / FALLOFF, -INITIAL_LIMIT, INITIAL_LIMIT);
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
