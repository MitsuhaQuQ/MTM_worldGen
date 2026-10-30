package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouterWithOnlyNoises;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldGenSettings;

import java.util.OptionalLong;

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
                && unwrap(noise.router().continents()) instanceof TerrainClimate climate) {
            return climate.logic();
        }
        return null;
    }

    /**
     * Strips holder wrappers. Generators decoded from level.dat (and every world created through the Create World
     * screen, which round-trips its settings through the codec) wrap inline density functions in HolderHolder.
     */
    private static DensityFunction unwrap(DensityFunction function) {
        while (function instanceof DensityFunctions.HolderHolder holder) {
            function = holder.function().value();
        }
        return function;
    }

    /**
     * World settings for a new MTM world: the vanilla nether and end, and an overworld that uses the MTM generator
     * and the taller MTM dimension type. Both loaders' world presets build worlds through this. Reads the config
     * file once, so the generator and the dimension type always agree on the build height.
     */
    public static WorldGenSettings createSettings(RegistryAccess access, long seed, boolean generateFeatures,
                                                  boolean bonusChest) {
        WorldgenSettings settings = MtmConfig.load();
        Registry<LevelStem> dimensions = DimensionType.defaultDimensions(access, seed);
        return new WorldGenSettings(seed, generateFeatures, bonusChest,
                WorldGenSettings.withOverworld(dimensions, dimensionType(settings), create(access, seed, settings)));
    }

    /**
     * Vanilla overworld dimension type with the build range derived from the settings. Stored inline in level.dat,
     * so it needs no registration and leaves vanilla worlds untouched.
     */
    public static Holder<DimensionType> dimensionType(WorldgenSettings settings) {
        return Holder.direct(DimensionType.create(OptionalLong.empty(), true, false, false, true, 1.0, false, false,
                true, false, true, settings.minBuildY(), settings.buildHeight(), settings.buildHeight(),
                BlockTags.INFINIBURN_OVERWORLD, DimensionType.OVERWORLD_EFFECTS, 0.0F));
    }

    public static ChunkGenerator create(RegistryAccess access, long seed) {
        return create(access, seed, MtmConfig.load());
    }

    public static ChunkGenerator create(RegistryAccess access, long seed, WorldgenSettings worldSettings) {
        NoiseGeneratorSettings vanilla = access.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        NoiseSettings vanillaNoise = vanilla.noiseSettings();
        NoiseSettings noise = NoiseSettings.create(worldSettings.minBuildY(), worldSettings.buildHeight(), vanillaNoise.noiseSamplingSettings(),
                vanillaNoise.topSlideSettings(), vanillaNoise.bottomSlideSettings(),
                vanillaNoise.noiseSizeHorizontal(), vanillaNoise.noiseSizeVertical(), vanillaNoise.terrainShaper());
        NoiseRouterWithOnlyNoises r = vanilla.noiseRouter();
        NoiseRouterWithOnlyNoises router = new NoiseRouterWithOnlyNoises(
                r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                r.temperature(), r.vegetation(), TerrainClimate.unbound(TerrainClimate.Kind.CONTINENTS, worldSettings), r.erosion(),
                TerrainClimate.unbound(TerrainClimate.Kind.DEPTH, worldSettings), r.ridges(),
                // Interpolated like vanilla's final density: evaluated on the sparse noise cell grid instead of for
                // every block. The density is linear around the surface, so interpolation keeps the surface in place
                TerrainDensity.unbound(true, worldSettings),
                DensityFunctions.interpolated(TerrainDensity.unbound(false, worldSettings)),
                r.veinToggle(), r.veinRidged(), r.veinGap());
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                noise, vanilla.defaultBlock(), vanilla.defaultFluid(), router,
                SurfaceRules.sequence(beachRule(), vanilla.surfaceRule()), worldSettings.general.seaLevel, vanilla.disableMobGeneration(),
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
