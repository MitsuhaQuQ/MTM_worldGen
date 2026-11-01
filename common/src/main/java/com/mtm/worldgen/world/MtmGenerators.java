package com.mtm.worldgen.world;

import com.mojang.serialization.Lifecycle;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/** Builds the MTM overworld generator: vanilla noise generator with MTM terrain density. */
public final class MtmGenerators {
    /**
     * Density function type of {@link TerrainDensity}. Each loader registers {@link TerrainDensity#CODEC} under
     * this id itself, because Forge freezes vanilla registries before mod construction.
     */
    public static final ResourceLocation TERRAIN_DENSITY_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "terrain");
    /** Density function type of {@link TerrainCaves}, registered by each loader like the terrain density. */
    public static final ResourceLocation CAVES_DENSITY_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "caves");
    /** Density function type of {@link TerrainClimate}, registered by each loader like the terrain density. */
    public static final ResourceLocation CLIMATE_DENSITY_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "climate");
    /** Biome source type of {@link MtmBiomeSource}, registered by each loader like the density types. */
    public static final ResourceLocation BIOME_SOURCE_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "terrain");
    /** Surface rule condition type of {@link TerrainCondition}, registered by each loader like the density type. */
    public static final ResourceLocation TERRAIN_CONDITION_ID = new ResourceLocation(MtmWorldgen.MOD_ID, "terrain");

    /** The registries that building an MTM world reads. */
    public record Lookups(HolderGetter<Biome> biomes, HolderGetter<DimensionType> dimensionTypes,
                          HolderGetter<DensityFunction> densityFunctions, HolderGetter<NormalNoise.NoiseParameters> noises) {
    }

    private MtmGenerators() {
    }

    /** The terrain logic of a level, or null if it does not use an MTM generator. */
    public static TerrainLogic terrainLogic(ServerLevel level) {
        return terrainLogic(level.getChunkSource().randomState());
    }

    /** The terrain logic of a seeded generator state, or null if it does not belong to an MTM generator. */
    public static TerrainLogic terrainLogic(RandomState random) {
        return unwrap(random.router().continents()) instanceof TerrainClimate climate ? climate.logic() : null;
    }

    /** The terrain logic behind the climate sampler of an MTM generator. */
    static TerrainLogic terrainLogic(Climate.Sampler sampler) {
        return ((TerrainClimate) unwrap(sampler.continentalness())).logic();
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
     * The dimensions of a new world. Those of the MTM world preset get the generator and dimension type the config file
     * asks for; both come from one reading of the file, so they always agree on the build height. Others stay as they are.
     */
    public static WorldDimensions applyConfig(WorldDimensions dimensions) {
        if (!(dimensions.overworld() instanceof NoiseBasedChunkGenerator generator)
                || !(generator.getBiomeSource() instanceof MtmBiomeSource source) || !source.isPlaceholder()) {
            return dimensions;
        }
        WorldgenSettings settings = MtmConfig.load();
        return new WorldDimensions(WorldDimensions.withOverworld(dimensions.dimensions(),
                source.lookups().dimensionTypes().getOrThrow(dimensionType(settings.buildHeight())),
                create(source.lookups(), generator.generatorSettings(), settings)));
    }

    /**
     * Key of the dimension type with a build height. Levels must name a registered dimension type, and registries are
     * the same for every world, so each possible height has its own type and a world keeps its height in its level.dat.
     */
    private static ResourceKey<DimensionType> dimensionType(int buildHeight) {
        return ResourceKey.create(Registries.DIMENSION_TYPE, new ResourceLocation(MtmWorldgen.MOD_ID, "overworld_" + buildHeight));
    }

    /** Adds the vanilla overworld type with every possible MTM build height to a registry being loaded. */
    public static void registerDimensionTypes(WritableRegistry<DimensionType> registry) {
        DimensionType overworld = registry.getOrThrow(BuiltinDimensionTypes.OVERWORLD);
        for (int height : WorldgenSettings.possibleBuildHeights()) {
            registry.register(dimensionType(height), new DimensionType(overworld.fixedTime(), overworld.hasSkyLight(),
                    overworld.hasCeiling(), overworld.ultraWarm(), overworld.natural(), overworld.coordinateScale(),
                    overworld.bedWorks(), overworld.respawnAnchorWorks(), overworld.minY(), height, height,
                    overworld.infiniburn(), overworld.effectsLocation(), overworld.ambientLight(),
                    overworld.monsterSettings()), Lifecycle.stable());
        }
    }

    /** The seeded state of a generator, as a level would build it. */
    public static RandomState randomState(RegistryAccess access, ChunkGenerator generator, long seed) {
        return RandomState.create(((NoiseBasedChunkGenerator) generator).generatorSettings().value(),
                access.lookupOrThrow(Registries.NOISE), seed);
    }

    public static ChunkGenerator create(RegistryAccess access, Holder<NoiseGeneratorSettings> vanillaSettings,
                                        WorldgenSettings worldSettings) {
        return create(new Lookups(access.lookupOrThrow(Registries.BIOME), access.lookupOrThrow(Registries.DIMENSION_TYPE),
                access.lookupOrThrow(Registries.DENSITY_FUNCTION), access.lookupOrThrow(Registries.NOISE)), vanillaSettings, worldSettings);
    }

    /**
     * Vanilla's cave density from NoiseRouterData#underground, which is private. The term that narrows caves close to
     * the surface is left out, because {@link TerrainCaves} keeps them away from it altogether.
     */
    private static DensityFunction caves(Lookups lookups) {
        DensityFunction layer = DensityFunctions.mul(DensityFunctions.constant(4.0),
                DensityFunctions.noise(lookups.noises().getOrThrow(Noises.CAVE_LAYER), 8.0).square());
        DensityFunction cheese = DensityFunctions.add(DensityFunctions.constant(0.27),
                DensityFunctions.noise(lookups.noises().getOrThrow(Noises.CAVE_CHEESE), 2.0 / 3.0)).clamp(-1.0, 1.0);
        DensityFunction open = DensityFunctions.min(
                DensityFunctions.min(DensityFunctions.add(layer, cheese), vanillaCave(lookups, "entrances")),
                DensityFunctions.add(vanillaCave(lookups, "spaghetti_2d"), vanillaCave(lookups, "spaghetti_roughness_function")));
        DensityFunction pillars = vanillaCave(lookups, "pillars");
        return DensityFunctions.max(open, DensityFunctions.rangeChoice(pillars, -1000000.0, 0.03,
                DensityFunctions.constant(-1000000.0), pillars));
    }

    private static DensityFunction vanillaCave(Lookups lookups, String name) {
        return new DensityFunctions.HolderHolder(lookups.densityFunctions().getOrThrow(
                ResourceKey.create(Registries.DENSITY_FUNCTION, new ResourceLocation("overworld/caves/" + name))));
    }

    private static ChunkGenerator create(Lookups lookups, Holder<NoiseGeneratorSettings> vanillaSettings, WorldgenSettings worldSettings) {
        NoiseGeneratorSettings vanilla = vanillaSettings.value();
        NoiseSettings vanillaNoise = vanilla.noiseSettings();
        NoiseSettings noise = NoiseSettings.create(worldSettings.minBuildY(), worldSettings.buildHeight(),
                vanillaNoise.noiseSizeHorizontal(), vanillaNoise.noiseSizeVertical());
        NoiseRouter r = vanilla.noiseRouter();
        NoiseRouter router = new NoiseRouter(
                r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                r.temperature(), r.vegetation(), TerrainClimate.unbound(TerrainClimate.Kind.CONTINENTS, worldSettings), r.erosion(),
                TerrainClimate.unbound(TerrainClimate.Kind.DEPTH, worldSettings), r.ridges(),
                // Interpolated like vanilla's final density: evaluated on the sparse noise cell grid instead of for
                // every block. The density is linear around the surface, so interpolation keeps the surface in place
                TerrainDensity.unbound(true, worldSettings),
                // Noodle caves stay outside like in vanilla: they bring interpolators of their own
                DensityFunctions.min(
                        DensityFunctions.interpolated(DensityFunctions.min(TerrainDensity.unbound(false, worldSettings),
                                TerrainCaves.unbound(caves(lookups), worldSettings))),
                        TerrainCaves.unbound(vanillaCave(lookups, "noodle"), worldSettings)),
                r.veinToggle(), r.veinRidged(), r.veinGap());
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                noise, vanilla.defaultBlock(), vanilla.defaultFluid(), router,
                SurfaceRules.sequence(terrainSurfaceRule(), vanilla.surfaceRule()), vanilla.spawnTarget(),
                worldSettings.general.seaLevel, vanilla.disableMobGeneration(), vanilla.aquifersEnabled(), vanilla.oreVeinsEnabled(), vanilla.useLegacyRandomSource());

        return new NoiseBasedChunkGenerator(new MtmBiomeSource(lookups, worldSettings), Holder.direct(settings));
    }

    /**
     * Terrain driven surfaces, ahead of the vanilla rules so they win over biome surfaces: snow, then bare rock
     * with stone patches, then beaches. Features need soil, so trees and grass stay off rock by themselves.
     */
    private static SurfaceRules.RuleSource terrainSurfaceRule() {
        SurfaceRules.RuleSource stone = SurfaceRules.state(Blocks.STONE.defaultBlockState());
        SurfaceRules.RuleSource patchStone = SurfaceRules.state(Blocks.ANDESITE.defaultBlockState());
        return SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), SurfaceRules.sequence(
                SurfaceRules.ifTrue(condition(TerrainCondition.Kind.SNOW), SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.SNOW_BLOCK.defaultBlockState())),
                        SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, stone))),
                // Deep enough to cover the tall faces of cliffs, where one column shows many blocks
                SurfaceRules.ifTrue(condition(TerrainCondition.Kind.ROCK), SurfaceRules.ifTrue(SurfaceRules.DEEP_UNDER_FLOOR, SurfaceRules.sequence(
                        SurfaceRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_DARK), SurfaceRules.state(Blocks.ANDESITE.defaultBlockState())),
                        SurfaceRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_TUFF), SurfaceRules.state(Blocks.TUFF.defaultBlockState())),
                        SurfaceRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_PALE), SurfaceRules.state(Blocks.DIORITE.defaultBlockState())),
                        SurfaceRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_SAND), SurfaceRules.state(Blocks.ANDESITE.defaultBlockState())),
                        SurfaceRules.ifTrue(condition(TerrainCondition.Kind.ROCK_PATCH), patchStone), stone))),
                // Their vanilla surfaces scatter stone and gravel patches regardless of slope, which reads as rock pasted on hills
                SurfaceRules.ifTrue(SurfaceRules.isBiome(Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_SAVANNA, Biomes.WINDSWEPT_GRAVELLY_HILLS), SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(-1, 0),
                                SurfaceRules.state(Blocks.GRASS_BLOCK.defaultBlockState()))),
                        SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.state(Blocks.DIRT.defaultBlockState())))),
                SurfaceRules.ifTrue(condition(TerrainCondition.Kind.GRAVEL_SHORE), SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR,
                        SurfaceRules.state(Blocks.GRAVEL.defaultBlockState()))),
                SurfaceRules.ifTrue(condition(TerrainCondition.Kind.ROCK_SHORE), SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR,
                        SurfaceRules.sequence(SurfaceRules.ifTrue(condition(TerrainCondition.Kind.ROCK_PATCH), patchStone), stone))),
                SurfaceRules.ifTrue(condition(TerrainCondition.Kind.BEACH), SurfaceRules.sequence(
                        SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.state(Blocks.SAND.defaultBlockState())),
                        SurfaceRules.ifTrue(SurfaceRules.DEEP_UNDER_FLOOR,
                                SurfaceRules.state(Blocks.SANDSTONE.defaultBlockState()))))));
    }

    private static TerrainCondition condition(TerrainCondition.Kind kind) {
        return new TerrainCondition(kind);
    }
}
