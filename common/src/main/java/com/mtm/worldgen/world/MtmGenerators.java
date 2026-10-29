package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterWithOnlyNoises;
import net.minecraft.world.level.levelgen.SurfaceRules;

/** Builds the MTM overworld generator: vanilla noise generator with MTM terrain density. */
public final class MtmGenerators {
    public static final ResourceLocation WORLD_TYPE_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "realistic");
    /**
     * Density function type of {@link TerrainDensity}. Each loader registers {@link TerrainDensity#CODEC} under
     * this id itself, because Forge freezes vanilla registries before mod construction.
     */
    public static final ResourceLocation TERRAIN_DENSITY_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "terrain");
    /** Density function type of {@link TerrainClimate}, registered by each loader like the terrain density. */
    public static final ResourceLocation CLIMATE_DENSITY_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "climate");
    /** Surface rule condition type of {@link BeachCondition}, registered by each loader like the density type. */
    public static final ResourceLocation BEACH_CONDITION_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "beach");

    private MtmGenerators() {
    }

    /** The terrain logic driving a chunk generator, or null if it is not an MTM generator. */
    public static TerrainLogic terrainLogic(ChunkGenerator generator) {
        if (generator instanceof NoiseBasedChunkGenerator noise
                && noise.router().finalDensity() instanceof TerrainDensity density) {
            return density.logic();
        }
        return null;
    }

    public static ChunkGenerator create(RegistryAccess access, long seed) {
        NoiseGeneratorSettings vanilla = access.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseRouterWithOnlyNoises r = vanilla.noiseRouter();
        NoiseRouterWithOnlyNoises router = new NoiseRouterWithOnlyNoises(
                r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                r.temperature(), r.vegetation(), TerrainClimate.CONTINENTS, r.erosion(), TerrainClimate.DEPTH, r.ridges(),
                TerrainDensity.INITIAL, TerrainDensity.FINAL,
                r.veinToggle(), r.veinRidged(), r.veinGap());
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                vanilla.noiseSettings(), vanilla.defaultBlock(), vanilla.defaultFluid(), router,
                SurfaceRules.sequence(beachRule(), vanilla.surfaceRule()), vanilla.seaLevel(), vanilla.disableMobGeneration(),
                vanilla.aquifersEnabled(), vanilla.oreVeinsEnabled(), vanilla.useLegacyRandomSource());

        BiomeSource biomes = MultiNoiseBiomeSource.Preset.OVERWORLD.biomeSource(access.registryOrThrow(Registry.BIOME_REGISTRY));
        return new NoiseBasedChunkGenerator(
                access.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY),
                access.registryOrThrow(Registry.NOISE_REGISTRY),
                biomes, seed, Holder.direct(settings));
    }

    /** Sand over sandstone on beach columns; runs before the vanilla rules so it wins over biome surfaces. */
    private static SurfaceRules.RuleSource beachRule() {
        return SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(),
                SurfaceRules.ifTrue(BeachCondition.INSTANCE, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.state(Blocks.SAND.defaultBlockState())),
                        SurfaceRules.ifTrue(SurfaceRules.DEEP_UNDER_FLOOR,
                                SurfaceRules.state(Blocks.SANDSTONE.defaultBlockState())))));
    }
}
