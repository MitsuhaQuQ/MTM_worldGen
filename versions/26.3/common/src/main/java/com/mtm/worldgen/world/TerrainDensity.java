package com.mtm.worldgen.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Terrain density: positive below the surface, negative above. Serialized without the seed; the seed comes in when
 * vanilla compiles the function for a world ({@link SeedCapture}).
 * <p>
 * The surface variant is the router's preliminary surface level instead: the height vanilla's aquifer and surface rules
 * take for the ground, worked out as 1.20.1 found it from the initial density.
 */
public final class TerrainDensity implements DensityFunction {
    public static final MapCodec<TerrainDensity> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.BOOL.optionalFieldOf("surface", false).forGetter(density -> density.surface),
            Codec.STRING.fieldOf("settings").forGetter(density -> density.settingsJson)
    ).apply(instance, TerrainDensity::new));

    private static final float FALLOFF = 8.0F;
    /**
     * How far below the real surface the preliminary surface sits, as in vanilla. The aquifer adds 8 blocks to
     * the preliminary surface and floods with sea water only when that point is below sea level; with the
     * preliminary surface at the real surface, land slightly below sea level counted as underground and stayed
     * dry next to vertical walls of sea water.
     */
    private static final double PRELIMINARY_SURFACE_DEPTH = 8.0;
    /**
     * Vanilla searched the preliminary surface in steps of a noise cell, on the corners of 4 block columns, and so
     * does this: the highest cell boundary below the preliminary height.
     */
    private static final int SURFACE_STEP = 8;
    private static final int SURFACE_GRID = 4;

    private final boolean surface;
    /** The world's locked settings; serialized with the function, so they live in level.dat. */
    private final String settingsJson;
    private final WorldgenSettings settings;

    private TerrainDensity(boolean surface, String settingsJson) {
        this.surface = surface;
        this.settingsJson = settingsJson;
        this.settings = WorldgenSettings.fromJson(settingsJson);
    }

    /** Placeholder for a router; it learns the seed when vanilla compiles it. */
    public static TerrainDensity unbound(boolean surface, WorldgenSettings settings) {
        return new TerrainDensity(surface, settings.toJson());
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new Sampler(SeedCapture.logic(settings), surface, settings.minBuildY());
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return surface ? Interval.of(settings.minBuildY(), settings.minBuildY() + settings.buildHeight()) : Interval.of(-1.0F, 1.0F);
    }

    @Override
    public int domainAxes() {
        return surface ? AXIS_X | AXIS_Z : ALL_AXES;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    private record Sampler(TerrainLogic logic, boolean surface, int minY) implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            if (surface) {
                int gridX = Math.floorDiv(x, SURFACE_GRID) * SURFACE_GRID;
                int gridZ = Math.floorDiv(z, SURFACE_GRID) * SURFACE_GRID;
                double below = logic.preliminaryHeight(gridX, gridZ) - PRELIMINARY_SURFACE_DEPTH;
                int step = Mth.ceil((below - minY) / SURFACE_STEP) - 1;
                return minY + Math.max(0, step) * SURFACE_STEP;
            }
            double depth = logic.solidHeight(x, y, z) - y;
            return (float) Mth.clamp(depth / FALLOFF, -1.0, 1.0);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, output, volume, this);
        }
    }
}
