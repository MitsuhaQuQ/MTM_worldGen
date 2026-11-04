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
 * Vanilla's cave density, kept away from the surface: rock stays solid close below it, so the ground, river beds and
 * lake beds never open up, and caves fade in further down. Learns the world seed the same way as {@link TerrainDensity}.
 */
public final class TerrainCaves implements DensityFunction {
    public static final MapCodec<TerrainCaves> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            DensityFunction.CODEC.fieldOf("caves").forGetter(caves -> caves.caves),
            Codec.STRING.fieldOf("settings").forGetter(caves -> caves.settingsJson)
    ).apply(instance, (caves, settings) -> new TerrainCaves(caves, settings, WorldgenSettings.fromJson(settings))));

    /** Blocks over which caves close toward the bottom of the world, like vanilla's bottom slide. */
    private static final double BOTTOM_FADE = 24.0;

    private final DensityFunction caves;
    private final String settingsJson;
    private final WorldgenSettings settings;

    private TerrainCaves(DensityFunction caves, String settingsJson, WorldgenSettings settings) {
        this.caves = caves;
        this.settingsJson = settingsJson;
        this.settings = settings;
    }

    /** Placeholder for a router; it learns the seed when vanilla compiles it. */
    public static TerrainCaves unbound(DensityFunction caves, WorldgenSettings settings) {
        return new TerrainCaves(caves, settings.toJson(), settings);
    }

    @Override
    public DensitySampler compileSampler(CompileContext context) {
        return new Sampler(SeedCapture.logic(settings), settings, caves.compileSampler(context));
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        DensityFunction rewritten = rule.rewrite(caves);
        return rewritten == caves ? this : new TerrainCaves(rewritten, settingsJson, settings);
    }

    @Override
    public Interval range() {
        return Interval.of(-1.0F, 1.0F);
    }

    @Override
    public int domainAxes() {
        return ALL_AXES;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    private record Sampler(TerrainLogic logic, WorldgenSettings settings, DensitySampler caves) implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            double share = share(logic.surfaceHeight(x, z), y);
            return share <= 0.0 ? 1.0F : fade(caves.sampleValue(context, x, y, z), share);
        }

        /**
         * Samples vanilla's caves as one volume, which their interpolators and caches are built for, and fades them
         * out toward the surface column by column. Sampling them block by block made chunks five times slower.
         */
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
            caves.sampleVolume(context, output, volume);
            for (int z = 0; z < volume.sizeZ(); z++) {
                for (int x = 0; x < volume.sizeX(); x++) {
                    double surface = logic.surfaceHeight(volume.blockX(x), volume.blockZ(z));
                    for (int y = 0; y < volume.sizeY(); y++) {
                        int index = volume.indexUnchecked(x, y, z);
                        double share = share(surface, volume.blockY(y));
                        output.set(index, share <= 0.0 ? 1.0F : fade(output.get(index), share));
                    }
                }
            }
        }

        private double share(double surface, int y) {
            double share = Mth.clamp((surface - y - settings.general.caveSurfaceMargin) / settings.general.caveFade, 0.0, 1.0);
            return share * Mth.clamp((y - settings.minBuildY()) / BOTTOM_FADE, 0.0, 1.0);
        }

        private static float fade(float caves, double share) {
            return (float) Mth.clamp(1.0 + (caves - 1.0) * share, -1.0, 1.0);
        }
    }
}
