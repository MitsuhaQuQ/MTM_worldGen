package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.TerrainLogic;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.mixin.NoiseFillAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.material.MaterialRules;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelResource;

import java.util.List;

/** Builds the MTM overworld generator: vanilla noise generator with MTM terrain density. */
public final class MtmGenerators {
    /**
     * Density function type of {@link TerrainDensity}. Each loader registers {@link TerrainDensity#CODEC} under
     * this id itself, because Forge freezes vanilla registries before mod construction.
     */
    public static final Identifier TERRAIN_DENSITY_ID = Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "terrain");
    /** Density function type of {@link TerrainCaves}, registered by each loader like the terrain density. */
    public static final Identifier CAVES_DENSITY_ID = Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "caves");
    /** Density function type of {@link TerrainClimate}, registered by each loader like the terrain density. */
    public static final Identifier CLIMATE_DENSITY_ID = Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "climate");
    /** Biome source type of {@link MtmBiomeSource}, registered by each loader like the density types. */
    public static final Identifier BIOME_SOURCE_ID = Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "terrain");
    /** Material condition type of {@link TerrainCondition}, registered by each loader like the density type. */
    public static final Identifier TERRAIN_CONDITION_ID = Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "terrain");

    /** Cell size of the terrain's interpolation, as vanilla's overworld noise cells. */
    private static final int CELL_WIDTH = 4;
    private static final int CELL_HEIGHT = 8;
    /** Vanilla interpolates its chunk surface level over this width. */
    private static final int SURFACE_CELL_WIDTH = 16;

    /** The registries that building an MTM world reads. */
    public record Lookups(HolderGetter<Biome> biomes, HolderGetter<DimensionType> dimensionTypes,
                          HolderGetter<DensityFunction> densityFunctions, HolderGetter<NormalNoise> noises) {
    }

    private MtmGenerators() {
    }

    /** The terrain logic of a level, or null if it does not use an MTM generator. */
    public static TerrainLogic terrainLogic(ServerLevel level) {
        return terrainLogic(level.getChunkSource().randomState());
    }

    /**
     * Points the terrain logic of an MTM overworld at its world folder, where eroded regions are kept between
     * sessions. Called when the level is created, before it generates anything.
     */
    public static void useCacheDirectory(ServerLevel level) {
        TerrainLogic logic = terrainLogic(level);
        if (logic != null && level.dimension() == Level.OVERWORLD) {
            logic.useCacheDirectory(level.getServer().getWorldPath(LevelResource.ROOT).resolve(MtmWorldgen.MOD_ID).resolve("erosion"));
        }
    }

    /** The terrain logic of a seeded generator state, or null if it does not belong to an MTM generator. */
    public static TerrainLogic terrainLogic(RandomState random) {
        return ((TerrainLogicHolder) (Object) random).mtm$terrainLogic();
    }

    /**
     * The terrain logic a new seeded generator state belongs to, from the settings its router carries; null if it is
     * no MTM router. Called once for each state, which keeps the answer.
     */
    public static TerrainLogic findTerrainLogic(NoiseRouter router, long seed) {
        return unwrap(router.continents()) instanceof TerrainClimate climate ? TerrainLogic.forWorld(seed, climate.settings()) : null;
    }

    /** The terrain logic behind the climate sampler of an MTM generator. */
    static TerrainLogic terrainLogic(Climate.Sampler sampler) {
        return ((TerrainClimate.Sampler) sampler.continentalness().sampler()).logic();
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
        return ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath(MtmWorldgen.MOD_ID, "overworld_" + buildHeight));
    }

    /** Adds the vanilla overworld type with every possible MTM build height to a registry being loaded. */
    public static void registerDimensionTypes(WritableRegistry<DimensionType> registry) {
        DimensionType overworld = registry.getValueOrThrow(BuiltinDimensionTypes.OVERWORLD);
        for (int height : WorldgenSettings.possibleBuildHeights()) {
            registry.register(dimensionType(height), new DimensionType(overworld.hasFixedTime(), overworld.hasSkyLight(),
                    overworld.hasCeiling(), overworld.hasEnderDragonFight(), overworld.coordinateScale(), overworld.minY(),
                    height, height, overworld.infiniburn(), overworld.ambientLight(), overworld.monsterSettings(),
                    overworld.skybox(), overworld.cardinalLightType(), overworld.attributes(), overworld.timelines(),
                    overworld.defaultClock()), RegistrationInfo.BUILT_IN);
        }
    }

    /** The seeded state of a generator, as a level would build it. */
    public static RandomState randomState(RegistryAccess access, ChunkGenerator generator, long seed) {
        return RandomState.create(access.lookupOrThrow(Registries.NOISE), seed,
                ((NoiseBasedChunkGenerator) generator).generatorSettings().value());
    }

    /**
     * Fills a chunk from the noise alone, without surface and carvers, on this thread: what vanilla's fillFromNoise did
     * before 26.1. Other generators, such as a superflat test world's, only lay their blocks, which this runs as it is.
     */
    public static void fillTerrain(ChunkGenerator generator, RandomState random, StructureManager structures, ChunkAccess chunk) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            generator.buildTerrain(chunk, Blender.empty(), random, structures, null, null, java.util.Set.of()).join();
            return;
        }
        NoiseSettings settings = noise.generatorSettings().value().noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        NoiseFillAccess access = (NoiseFillAccess) (Object) noise;
        try (var noiseChunk = access.mtm$createNoiseChunk(chunk, structures, Blender.empty(), random, settings)) {
            access.mtm$doFill(noiseChunk, chunk);
        }
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
        DensityFunction layer = DensityFunctions.mul(DensityFunctions.constant(4.0F),
                DensityFunctions.noise(lookups.noises().getOrThrow(Noises.CAVE_LAYER), 8.0).square());
        DensityFunction cheese = DensityFunctions.add(DensityFunctions.constant(0.27F),
                DensityFunctions.noise(lookups.noises().getOrThrow(Noises.CAVE_CHEESE), 2.0 / 3.0)).clamp(-1.0F, 1.0F);
        DensityFunction open = DensityFunctions.min(
                DensityFunctions.min(DensityFunctions.add(layer, cheese), vanillaCave(lookups, "entrances")),
                DensityFunctions.add(vanillaCave(lookups, "spaghetti_2d"), vanillaCave(lookups, "spaghetti_roughness_function")));
        DensityFunction pillars = vanillaCave(lookups, "pillars");
        return DensityFunctions.max(open, DensityFunctions.rangeChoice(pillars, -1000000.0F, 0.03F,
                DensityFunctions.constant(-1000000.0F), pillars));
    }

    /** Terrain with caves cut into it, or terrain alone in a world without caves. */
    private static DensityFunction finalDensity(Lookups lookups, WorldgenSettings worldSettings) {
        if (!worldSettings.general.caves) {
            return DensityFunctions.interpolated(TerrainDensity.unbound(false, worldSettings), CELL_WIDTH, CELL_HEIGHT);
        }
        // Noodle caves stay outside like in vanilla: they bring interpolators of their own
        return DensityFunctions.min(
                DensityFunctions.interpolated(DensityFunctions.min(TerrainDensity.unbound(false, worldSettings),
                        TerrainCaves.unbound(caves(lookups), worldSettings)), CELL_WIDTH, CELL_HEIGHT),
                TerrainCaves.unbound(vanillaCave(lookups, "noodle"), worldSettings));
    }

    private static DensityFunction vanillaCave(Lookups lookups, String name) {
        return new DensityFunctions.HolderHolder(lookups.densityFunctions().getOrThrow(
                ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.withDefaultNamespace("overworld/caves/" + name))));
    }

    private static ChunkGenerator create(Lookups lookups, Holder<NoiseGeneratorSettings> vanillaSettings, WorldgenSettings worldSettings) {
        NoiseGeneratorSettings vanilla = vanillaSettings.value();
        NoiseSettings noise = NoiseSettings.create(worldSettings.minBuildY(), worldSettings.buildHeight());
        NoiseRouter r = vanilla.noiseRouter();
        DensityFunction depth = TerrainClimate.unbound(TerrainClimate.Kind.DEPTH, worldSettings);
        DensityFunction surface = TerrainDensity.unbound(true, worldSettings);
        NoiseRouter router = new NoiseRouter(
                r.temperature(), r.vegetation(), TerrainClimate.unbound(TerrainClimate.Kind.CONTINENTS, worldSettings), r.erosion(),
                depth, r.ridges(),
                DensityFunctions.interpolated(surface, SURFACE_CELL_WIDTH, 1),
                finalDensity(lookups, worldSettings));
        // Vanilla's aquifer, on the MTM ground: the deep dark region it keeps dry follows the MTM depth
        Aquifer.Config aquifers = vanilla.aquifers().map(a -> new Aquifer.Config(a.barrierNoise(), a.fluidLevelFloodednessNoise(),
                a.fluidLevelSpreadNoise(), a.lavaNoise(), OverworldBiomeBuilder.deepDarkRegion(r.erosion(), depth), surface)).orElse(null);
        NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                noise, vanilla.defaultBlock(), vanilla.defaultFluid(), router,
                Holder.direct(MaterialRules.sequence(terrainMaterialRule(lookups.biomes()), vanillaRule(vanilla, worldSettings.general.ores))),
                vanilla.spawnTarget(), worldSettings.general.seaLevel, vanilla.disableMobGeneration(),
                java.util.Optional.ofNullable(aquifers), vanilla.useLegacyRandomSource(), vanilla.debugFunctions());

        return new NoiseBasedChunkGenerator(new MtmBiomeSource(lookups, worldSettings), Holder.direct(settings));
    }

    /**
     * Vanilla's material rule. Ore veins are part of it; a world without ores leaves them out, which works on the
     * rule's top level, where vanilla lists the veins by name.
     */
    private static MaterialRule vanillaRule(NoiseGeneratorSettings vanilla, boolean ores) {
        MaterialRule rule = new MaterialRule.HolderHolder(vanilla.materialRule());
        if (ores) {
            return rule;
        }
        MaterialRule top = vanilla.materialRule().value();
        while (top instanceof MaterialRule.HolderHolder holder) {
            top = holder.holder().value();
        }
        if (!(top instanceof SequenceRule sequence)) {
            return rule;
        }
        List<MaterialRule> kept = sequence.sequence().stream().filter(part -> !(part instanceof MaterialRule.HolderHolder holder
                && holder.holder().unwrapKey().map(key -> key.identifier().getPath().endsWith("ore_vein")).orElse(false))).toList();
        return MaterialRules.sequence(kept);
    }

    /** Whether generator settings place ore veins: vanilla's rule names them on its top level. */
    public static boolean hasOreVeins(NoiseGeneratorSettings settings) {
        MaterialRule top = settings.materialRule().value();
        while (top instanceof MaterialRule.HolderHolder holder) {
            top = holder.holder().value();
        }
        if (!(top instanceof SequenceRule sequence)) {
            return false;
        }
        for (MaterialRule part : sequence.sequence()) {
            MaterialRule inner = part;
            while (inner instanceof MaterialRule.HolderHolder holder) {
                if (holder.holder().unwrapKey().map(key -> key.identifier().getPath().endsWith("ore_vein")).orElse(false)) {
                    return true;
                }
                inner = holder.holder().value();
            }
            if (inner instanceof SequenceRule nested && hasOreVeins(nested)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOreVeins(SequenceRule sequence) {
        for (MaterialRule part : sequence.sequence()) {
            if (part instanceof MaterialRule.HolderHolder holder
                    && holder.holder().unwrapKey().map(key -> key.identifier().getPath().endsWith("ore_vein")).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Terrain driven surfaces, ahead of the vanilla rules so they win over biome surfaces: snow, then bare rock
     * with stone patches, then beaches. Features need soil, so trees and grass stay off rock by themselves.
     */
    private static MaterialRule terrainMaterialRule(HolderGetter<Biome> biomes) {
        MaterialCondition onFloor = MaterialRules.stoneDepthCheck(0, false, CaveSurface.FLOOR);
        MaterialCondition underFloor = MaterialRules.stoneDepthCheck(0, true, CaveSurface.FLOOR);
        MaterialCondition deepUnderFloor = MaterialRules.stoneDepthCheck(0, true, 6, CaveSurface.FLOOR);
        MaterialRule stone = MaterialRules.state(Blocks.STONE.defaultBlockState());
        MaterialRule patchStone = MaterialRules.state(Blocks.ANDESITE.defaultBlockState());
        return MaterialRules.ifTrue(MaterialRules.abovePreliminarySurface(), MaterialRules.sequence(
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.SNOW), MaterialRules.sequence(
                        MaterialRules.ifTrue(onFloor, MaterialRules.state(Blocks.SNOW_BLOCK.defaultBlockState())),
                        MaterialRules.ifTrue(underFloor, stone))),
                // Deep enough to cover the tall faces of cliffs, where one column shows many blocks
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.ROCK), MaterialRules.ifTrue(deepUnderFloor, MaterialRules.sequence(
                        MaterialRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_DARK), MaterialRules.state(Blocks.ANDESITE.defaultBlockState())),
                        MaterialRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_TUFF), MaterialRules.state(Blocks.TUFF.defaultBlockState())),
                        MaterialRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_PALE), MaterialRules.state(Blocks.DIORITE.defaultBlockState())),
                        MaterialRules.ifTrue(condition(TerrainCondition.Kind.STRATUM_SAND), MaterialRules.state(Blocks.ANDESITE.defaultBlockState())),
                        MaterialRules.ifTrue(condition(TerrainCondition.Kind.ROCK_PATCH), patchStone), stone))),
                // Their vanilla surfaces scatter stone and gravel patches regardless of slope, which reads as rock pasted on hills
                MaterialRules.ifTrue(MaterialRules.isBiome(biomes, Biomes.WINDSWEPT_HILLS, Biomes.WINDSWEPT_SAVANNA, Biomes.WINDSWEPT_GRAVELLY_HILLS), MaterialRules.sequence(
                        MaterialRules.ifTrue(onFloor, MaterialRules.ifTrue(MaterialRules.waterBlockCheck(-1, 0),
                                MaterialRules.state(Blocks.GRASS_BLOCK.defaultBlockState()))),
                        MaterialRules.ifTrue(underFloor, MaterialRules.state(Blocks.DIRT.defaultBlockState())))),
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.GRAVEL_SHORE), MaterialRules.ifTrue(underFloor,
                        MaterialRules.state(Blocks.GRAVEL.defaultBlockState()))),
                // Sediment bars along rivers, sand over sandstone like a beach, or gravel where the river runs steeper
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.RIVER_GRAVEL), MaterialRules.ifTrue(underFloor,
                        MaterialRules.state(Blocks.GRAVEL.defaultBlockState()))),
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.RIVER_SAND), MaterialRules.sequence(
                        MaterialRules.ifTrue(underFloor, MaterialRules.state(Blocks.SAND.defaultBlockState())),
                        MaterialRules.ifTrue(deepUnderFloor, MaterialRules.state(Blocks.SANDSTONE.defaultBlockState())))),
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.ROCK_SHORE), MaterialRules.ifTrue(underFloor,
                        MaterialRules.sequence(MaterialRules.ifTrue(condition(TerrainCondition.Kind.ROCK_PATCH), patchStone), stone))),
                MaterialRules.ifTrue(condition(TerrainCondition.Kind.BEACH), MaterialRules.sequence(
                        MaterialRules.ifTrue(underFloor, MaterialRules.sequence(
                                MaterialRules.ifTrue(condition(TerrainCondition.Kind.MOUTH_CLAY), MaterialRules.state(Blocks.CLAY.defaultBlockState())),
                                MaterialRules.state(Blocks.SAND.defaultBlockState()))),
                        MaterialRules.ifTrue(deepUnderFloor,
                                MaterialRules.state(Blocks.SANDSTONE.defaultBlockState()))))));
    }

    private static TerrainCondition condition(TerrainCondition.Kind kind) {
        return new TerrainCondition(kind);
    }
}
