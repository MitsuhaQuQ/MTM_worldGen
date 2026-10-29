package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.TerrainLogic;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterWithOnlyNoises;

/** Builds the MTM overworld generator: vanilla noise generator with MTM terrain density. */
public final class MtmGenerators {
    public static final ResourceLocation WORLD_TYPE_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "realistic");
    /**
     * Density function type of {@link TerrainDensity}. Each loader registers {@link TerrainDensity#CODEC} under
     * this id itself, because Forge freezes vanilla registries before mod construction.
     */
    public static final ResourceLocation TERRAIN_DENSITY_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "terrain");

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
                r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                TerrainDensity.INITIAL, TerrainDensity.FINAL,
                r.veinToggle(), r.veinRidged(), r.veinGap());
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                vanilla.noiseSettings(), vanilla.defaultBlock(), vanilla.defaultFluid(), router,
                vanilla.surfaceRule(), vanilla.seaLevel(), vanilla.disableMobGeneration(),
                vanilla.aquifersEnabled(), vanilla.oreVeinsEnabled(), vanilla.useLegacyRandomSource());

        BiomeSource biomes = MultiNoiseBiomeSource.Preset.OVERWORLD.biomeSource(access.registryOrThrow(Registry.BIOME_REGISTRY));
        return new NoiseBasedChunkGenerator(
                access.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY),
                access.registryOrThrow(Registry.NOISE_REGISTRY),
                biomes, seed, Holder.direct(settings));
    }
}
