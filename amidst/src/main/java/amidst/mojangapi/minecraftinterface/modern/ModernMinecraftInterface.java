package amidst.mojangapi.minecraftinterface.modern;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Stream;

import amidst.documentation.ThreadSafe;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.UnsupportedDimensionException;
import amidst.mojangapi.minecraftinterface.modern.Mc.McException;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.producer.MultiProducer;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;
import amidst.mojangapi.world.versionfeatures.FeatureKey;
import amidst.mojangapi.world.versionfeatures.VersionFeatures;
import amidst.util.ArrayCache;

/**
 * Talks to Minecraft 26.1 and later, whose classes have readable names.
 *
 * Since 1.18, Minecraft places biomes with a multi-noise biome source and
 * structures with structure sets from its built-in data pack. Instead of
 * reimplementing them, this interface loads the data pack the way a server
 * does and asks Minecraft's own classes for biomes, the world spawn and the
 * places of structures.
 */
@ThreadSafe
public class ModernMinecraftInterface implements MinecraftInterface {
	private static final Set<Dimension> SUPPORTED_DIMENSIONS = Collections.unmodifiableSet(EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER));
	/**
	 * Biomes are sampled at the top of the world. Up there, the climate picks
	 * the same biome as at the surface, but never one of the cave biomes.
	 */
	private static final int OVERWORLD_BIOME_Y = 316;
	private static final int NETHER_BIOME_Y = 64;
	private static final int QUART_SHIFT = 2;
	private static final int UNKNOWN_BIOME_ID = 127;
	private static final Executor SAME_THREAD = Runnable::run;

	public static ModernMinecraftInterface create(ClassLoader classLoader, RecognisedVersion recognisedVersion)
			throws MinecraftInterfaceCreationException {
		try {
			ModernMinecraftInterface result = new ModernMinecraftInterface(new Mc(classLoader), recognisedVersion);
			AmidstLogger.info("Loaded the worldgen data of Minecraft {}", recognisedVersion.getName());
			return result;
		} catch (McException | LinkageError e) {
			throw new MinecraftInterfaceCreationException("Unable to load the world generation of this Minecraft version", e);
		}
	}

	private final Mc mc;
	private final RecognisedVersion recognisedVersion;
	private final Object resourceManager;
	private final Object registryAccess;
	private final Map<String, Integer> biomeIds;
	private final BiomeList biomeList;
	private final Map<Object, Integer> biomeIdCache = new ConcurrentHashMap<>();
	private final MethodHandle getNoiseBiome;
	private final MethodHandle getBiome;
	private final ArrayCache<int[]> dataArray = ArrayCache.makeIntArrayCache(256);
	private Object templateManager;
	private World world;

	private ModernMinecraftInterface(Mc mc, RecognisedVersion recognisedVersion) {
		this.mc = mc;
		this.recognisedVersion = recognisedVersion;
		bootstrap();
		Object vanillaPack = mc.callStatic("net.minecraft.server.packs.repository.ServerPacksSource", "createVanillaPackSource");
		this.resourceManager = mc.create("net.minecraft.server.packs.resources.MultiPackResourceManager",
				mc.get("net.minecraft.server.packs.PackType", "SERVER_DATA"),
				Collections.singletonList(mc.call(vanillaPack, "fullResources")));
		this.registryAccess = loadWorldRegistries();
		this.biomeIds = ModernBiomes.assignIds(readBiomeKeys());
		this.biomeList = ModernBiomes.createBiomeList(biomeIds);
		this.getNoiseBiome = mc.handle("net.minecraft.world.level.biome.BiomeResolver", "getNoiseBiome", int.class, int.class, int.class);
		this.getBiome = mc.handle("net.minecraft.world.level.biome.BiomeManager", "getBiome", int.class, int.class, int.class);
	}

	/**
	 * Minecraft replaces System.out and System.err while bootstrapping, so
	 * they are put back afterwards.
	 */
	private void bootstrap() {
		PrintStream out = System.out;
		PrintStream err = System.err;
		try {
			mc.callStatic("net.minecraft.SharedConstants", "tryDetectVersion");
			mc.callStatic("net.minecraft.server.Bootstrap", "bootStrap");
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
	}

	/**
	 * Loads the registries of the worldgen layer from the vanilla data pack,
	 * with their tags, like WorldLoader does for a server.
	 */
	private Object loadWorldRegistries() {
		String registryLayer = "net.minecraft.server.RegistryLayer";
		Object layers = mc.callStatic(registryLayer, "createRegistryAccess");
		Object staticTags = mc.callStatic("net.minecraft.tags.TagLoader", "loadTagsForExistingRegistries",
				resourceManager, mc.call(layers, "getLayer", mc.get(registryLayer, "STATIC")));
		Object context = mc.callStatic("net.minecraft.tags.TagLoader", "buildUpdatedLookups",
				mc.call(layers, "getAccessForLoading", mc.get(registryLayer, "WORLD")), staticTags);
		CompletableFuture<?> loading = (CompletableFuture<?>) mc.callStatic("net.minecraft.resources.RegistryDataLoader", "load",
				resourceManager, context, mc.get("net.minecraft.resources.RegistryDataLoader", "WORLD_REGISTRIES"), SAME_THREAD);
		Object worldRegistries = loading.join();
		Object worldLayers = mc.call(layers, "replaceFrom", mc.get(registryLayer, "WORLD"), Collections.singletonList(worldRegistries));
		return mc.call(worldLayers, "compositeAccess");
	}

	private List<String> readBiomeKeys() {
		List<String> result = new ArrayList<>();
		Object biomes = mc.call(registryAccess, "lookupOrThrow", mc.get("net.minecraft.core.registries.Registries", "BIOME"));
		((Stream<?>) mc.call(biomes, "listElements")).forEach(holder -> result.add(keyOf(holder)));
		result.sort(Comparator.naturalOrder());
		return result;
	}

	private String keyOf(Object holder) {
		Optional<?> key = (Optional<?>) mc.call(holder, "unwrapKey");
		return key.map(k -> mc.call(k, "identifier").toString()).orElse("unregistered");
	}

	private int biomeIdOf(Object holder) {
		Integer result = biomeIdCache.get(holder);
		if (result == null) {
			result = biomeIds.getOrDefault(keyOf(holder), UNKNOWN_BIOME_ID);
			biomeIdCache.put(holder, result);
		}
		return result;
	}

	/**
	 * Structure templates are only needed for the start pieces of jigsaw
	 * structures like villages, so the template manager is made on first use.
	 */
	private synchronized Object getTemplateManager() {
		if (templateManager == null) {
			try {
				Path directory = Files.createTempDirectory("amidst-structures");
				directory.toFile().deleteOnExit();
				Object storage = mc.callStatic("net.minecraft.world.level.storage.LevelStorageSource", "createDefault", directory);
				Object access = mc.call(storage, "createAccess", "amidst");
				templateManager = mc.create("net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager",
						resourceManager,
						access,
						mc.callStatic("net.minecraft.util.datafix.DataFixers", "getDataFixer"),
						mc.get("net.minecraft.core.registries.BuiltInRegistries", "BLOCK"));
			} catch (IOException e) {
				throw new McException("unable to create a directory for structure templates", e);
			}
		}
		return templateManager;
	}

	private synchronized World worldFor(WorldOptions worldOptions) throws MinecraftInterfaceException {
		long seed = worldOptions.getWorldSeed().getLong();
		WorldType worldType = worldOptions.getWorldType();
		if (world == null || world.seed != seed || world.worldType != worldType) {
			try {
				world = new World(seed, worldType);
			} catch (McException e) {
				throw new MinecraftInterfaceException("unable to create the world", e);
			}
		}
		return world;
	}

	@Override
	public MinecraftInterface.WorldAccessor createWorldAccessor(WorldOptions worldOptions) throws MinecraftInterfaceException {
		World world = worldFor(worldOptions);
		try {
			return new WorldAccessor(world);
		} catch (McException e) {
			throw new MinecraftInterfaceException("unable to create the world", e);
		}
	}

	@Override
	public RecognisedVersion getRecognisedVersion() {
		return recognisedVersion;
	}

	@Override
	public void overrideFeatures(VersionFeatures.Builder features, WorldOptions worldOptions)
			throws MinecraftInterfaceException {
		World world = worldFor(worldOptions);
		ModernDimension overworld = world.overworld;
		ModernDimension nether = world.nether;
		features.withOverride(FeatureKey.BIOME_LIST, biomeList);
		features.withOverride(FeatureKey.WORLD_SPAWN_ORACLE, () -> {
			int[] chunk = overworld.getOriginChunk();
			return CoordinatesInWorld.from(chunk[0] << 4, chunk[1] << 4);
		});
		features.withOverride(FeatureKey.STRONGHOLD_PRODUCER, new ModernStructures.StrongholdProducer(overworld));
		features.withOverride(FeatureKey.VILLAGE_ICON_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.VILLAGE));
		features.withOverride(FeatureKey.PILLAGER_OUTPOST_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.PILLAGER_OUTPOST));
		features.withOverride(FeatureKey.DESERT_TEMPLE_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.DESERT));
		features.withOverride(FeatureKey.IGLOO_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.IGLOO));
		features.withOverride(FeatureKey.JUNGLE_TEMPLE_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.JUNGLE));
		features.withOverride(FeatureKey.WITCH_HUT_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.WITCH));
		features.withOverride(FeatureKey.MINESHAFT_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.MINESHAFT));
		features.withOverride(FeatureKey.OCEAN_MONUMENT_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.OCEAN_MONUMENT));
		features.withOverride(FeatureKey.WOODLAND_MANSION_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.WOODLAND_MANSION));
		features.withOverride(FeatureKey.OCEAN_RUINS_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.OCEAN_RUINS));
		features.withOverride(FeatureKey.SHIPWRECK_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.SHIPWRECK));
		features.withOverride(FeatureKey.BURIED_TREASURE_PRODUCER, overworldStructures(overworld, DefaultWorldIconTypes.BURIED_TREASURE));
		features.withOverride(FeatureKey.OTHER_STRUCTURES_PRODUCER, overworldStructures(overworld,
				DefaultWorldIconTypes.ANCIENT_CITY,
				DefaultWorldIconTypes.TRIAL_CHAMBERS,
				DefaultWorldIconTypes.TRAIL_RUINS,
				DefaultWorldIconTypes.RUINED_PORTAL,
				DefaultWorldIconTypes.ABANDONED_CAMP));
		features.withOverride(FeatureKey.NETHER_FORTRESS_PRODUCER, new MultiProducer<>(
				new ModernStructures.Producer(nether, Dimension.NETHER, DefaultWorldIconTypes.NETHER_FORTRESS),
				new ModernStructures.Producer(nether, Dimension.NETHER, DefaultWorldIconTypes.NETHER_FOSSIL, DefaultWorldIconTypes.RUINED_PORTAL)));
		features.withOverride(FeatureKey.BASTION_REMNANT_PRODUCER,
				new ModernStructures.Producer(nether, Dimension.NETHER, DefaultWorldIconTypes.BASTION_REMNANT));
	}

	private static ModernStructures.Producer overworldStructures(ModernDimension overworld, DefaultWorldIconTypes... types) {
		return new ModernStructures.Producer(overworld, Dimension.OVERWORLD, types);
	}

	private Object presetFor(WorldType worldType) {
		String presets = "net.minecraft.world.level.levelgen.presets.WorldPresets";
		switch (worldType) {
		case FLAT:
			return mc.get(presets, "FLAT");
		case AMPLIFIED:
			return mc.get(presets, "AMPLIFIED");
		case LARGE_BIOMES:
			return mc.get(presets, "LARGE_BIOMES");
		case DEFAULT:
			return mc.get(presets, "NORMAL");
		default:
			AmidstLogger.warn("Unsupported world type for this version: " + worldType);
			return mc.get(presets, "NORMAL");
		}
	}

	/**
	 * The dimensions of a world, as Minecraft creates them from a world preset.
	 */
	private class World {
		private final long seed;
		private final WorldType worldType;
		private final ModernDimension overworld;
		private final ModernDimension nether;
		private final long biomeZoomSeed;

		private World(long seed, WorldType worldType) {
			this.seed = seed;
			this.worldType = worldType;
			Object presets = mc.call(registryAccess, "lookupOrThrow",
					mc.get("net.minecraft.core.registries.Registries", "WORLD_PRESET"));
			Object preset = mc.call(mc.call(presets, "getOrThrow", presetFor(worldType)), "value");
			Object dimensions = mc.call(preset, "createWorldDimensions");
			this.overworld = createDimension(dimensions, "OVERWORLD");
			this.nether = createDimension(dimensions, "NETHER");
			this.biomeZoomSeed = (Long) mc.callStatic("net.minecraft.world.level.biome.BiomeManager", "obfuscateSeed", seed);
		}

		private ModernDimension createDimension(Object dimensions, String name) {
			Optional<?> levelStem = (Optional<?>) mc.call(dimensions, "get", mc.get("net.minecraft.world.level.dimension.LevelStem", name));
			Object stem = levelStem.orElseThrow(() -> new McException("the world preset has no dimension " + name));
			return new ModernDimension(mc, registryAccess, stem, seed, ModernMinecraftInterface.this::getTemplateManager);
		}
	}

	private class WorldAccessor implements MinecraftInterface.WorldAccessor {
		private final Object overworldResolver;
		private final Object netherResolver;
		private final Object overworldBiomeManager;
		private final Object netherBiomeManager;

		private WorldAccessor(World world) {
			this.overworldResolver = world.overworld.createBiomeResolver();
			this.netherResolver = world.nether.createBiomeResolver();
			String biomeManager = "net.minecraft.world.level.biome.BiomeManager";
			this.overworldBiomeManager = mc.create(biomeManager, overworldResolver, world.biomeZoomSeed);
			this.netherBiomeManager = mc.create(biomeManager, netherResolver, world.biomeZoomSeed);
		}

		@Override
		public <T> T getBiomeData(
				Dimension dimension,
				int x, int y, int width, int height,
				boolean useQuarterResolution, Function<int[], T> biomeDataMapper)
				throws MinecraftInterfaceException {
			Object source;
			int biomeY;
			switch (dimension) {
			case OVERWORLD:
				source = useQuarterResolution ? overworldResolver : overworldBiomeManager;
				biomeY = OVERWORLD_BIOME_Y;
				break;
			case NETHER:
				source = useQuarterResolution ? netherResolver : netherBiomeManager;
				biomeY = NETHER_BIOME_Y;
				break;
			default:
				throw new UnsupportedDimensionException(dimension);
			}
			MethodHandle method = useQuarterResolution ? getNoiseBiome : getBiome;
			int sampleY = useQuarterResolution ? biomeY >> QUART_SHIFT : biomeY;

			return dataArray.withArrayFaillible(width * height, data -> {
				try {
					for (int j = 0; j < height; j++) {
						for (int i = 0; i < width; i++) {
							Object biome = method.invoke(source, x + i, sampleY, y + j);
							data[i + j * width] = biomeIdOf(Objects.requireNonNull(biome));
						}
					}
				} catch (Throwable e) {
					throw new MinecraftInterfaceException("unable to get biome data", e);
				}
				return biomeDataMapper.apply(data);
			});
		}

		@Override
		public Set<Dimension> supportedDimensions() {
			return SUPPORTED_DIMENSIONS;
		}
	}
}
