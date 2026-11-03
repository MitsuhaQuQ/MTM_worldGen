package amidst.mojangapi.minecraftinterface.mtm;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import amidst.documentation.ThreadSafe;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.modern.ModernBiomes;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.NoopProducer;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;
import amidst.mojangapi.world.oracle.WorldSpawnOracle;
import amidst.mojangapi.world.versionfeatures.FeatureKey;
import amidst.mojangapi.world.versionfeatures.VersionFeatures;

/**
 * Reads biomes from the terrain logic of the MTM Worldgen mod instead of from Minecraft.
 *
 * The logic package of the mod is plain Java and only needs Gson, so it runs straight from the mod jar, without
 * Minecraft. Each world gets its settings from its level.dat (see the generator options read by
 * {@link amidst.mojangapi.file.nbt.LevelDatNbt}); worlds from a seed use the config file of the game directory, or the
 * defaults of the mod.
 *
 * Structures still follow the rules of the newest Minecraft version Amidst knows, so they are only approximate.
 */
@ThreadSafe
public class MtmMinecraftInterface implements MinecraftInterface {
	/** Structure placement in MTM worlds is vanilla, and 1.17 is the closest to 1.20 that Amidst has. */
	private static final RecognisedVersion STRUCTURE_RULES = RecognisedVersion._1_17;
	private static final String SETTINGS_CLASS = "com.mtm.worldgen.logic.WorldgenSettings";
	private static final String LOGIC_CLASS = "com.mtm.worldgen.logic.TerrainLogic";
	private static final String COLUMN_CLASS = "com.mtm.worldgen.logic.BlockColumn";
	private static final String CONFIG_FILE = "config/mtm_worldgen.json";
	/** Shown for biome ids that Amidst does not know, such as biomes of other mods named in the config. */
	private static final int UNKNOWN_BIOME = ModernBiomes.knownIds().get("minecraft:the_void");
	/** MTM places vanilla biomes only, so they keep the ids Amidst gives them in modern Minecraft. */
	private static final BiomeList BIOME_LIST = ModernBiomes.createBiomeList(ModernBiomes.knownIds());

	public static MtmMinecraftInterface create(Path modJar, Path gameDirectory) throws MinecraftInterfaceCreationException {
		try {
			// Gson comes from Amidst, like it comes from Minecraft in the game
			URLClassLoader classLoader = new URLClassLoader(
					new URL[] { modJar.toUri().toURL() },
					MtmMinecraftInterface.class.getClassLoader());
			MtmMinecraftInterface result = new MtmMinecraftInterface(classLoader, readConfig(gameDirectory));
			AmidstLogger.info("Loaded MTM terrain logic from '{}'", modJar);
			return result;
		} catch (MalformedURLException | ReflectiveOperationException e) {
			throw new MinecraftInterfaceCreationException("Unable to load the MTM terrain logic from " + modJar, e);
		} catch (UnsupportedClassVersionError e) {
			throw new MinecraftInterfaceCreationException("This version of MTM Worldgen needs a newer Java", e);
		}
	}

	private static String readConfig(Path gameDirectory) {
		if (gameDirectory == null) {
			return null;
		}
		Path config = gameDirectory.resolve(CONFIG_FILE);
		if (!Files.isRegularFile(config)) {
			return null;
		}
		try {
			AmidstLogger.info("Worlds from a seed use the MTM settings in '{}'", config);
			return new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
		} catch (IOException e) {
			AmidstLogger.warn(e, "Unable to read '{}', worlds from a seed use the default MTM settings", config);
			return null;
		}
	}

	private final MethodHandle settingsFromJson;
	private final Object defaultSettings;
	private final MethodHandle newLogic;
	private final MethodHandle sampleBiome;
	private final MethodHandle isVillageGround;
	private final MethodHandle findSpawn;
	private final MethodHandle columnX;
	private final MethodHandle columnZ;
	private final String configJson;
	private final Map<String, Integer> biomeIds = new ConcurrentHashMap<>();
	private Logic current;

	private MtmMinecraftInterface(ClassLoader classLoader, String configJson) throws ReflectiveOperationException {
		MethodHandles.Lookup lookup = MethodHandles.publicLookup();
		Class<?> settings = Class.forName(SETTINGS_CLASS, true, classLoader);
		Class<?> logic = Class.forName(LOGIC_CLASS, true, classLoader);
		Class<?> column = Class.forName(COLUMN_CLASS, true, classLoader);
		this.settingsFromJson = lookup.findStatic(settings, "fromJson", MethodType.methodType(settings, String.class));
		this.defaultSettings = settings.getField("DEFAULT").get(null);
		this.newLogic = lookup.findConstructor(logic, MethodType.methodType(void.class, long.class, settings));
		this.sampleBiome = lookup.findVirtual(logic, "sampleBiome", MethodType.methodType(String.class, double.class, double.class));
		this.isVillageGround = lookup.findVirtual(logic, "isVillageGround", MethodType.methodType(boolean.class, double.class, double.class));
		this.findSpawn = lookup.findVirtual(logic, "findSpawn", MethodType.methodType(column));
		this.columnX = lookup.findVirtual(column, "x", MethodType.methodType(int.class));
		this.columnZ = lookup.findVirtual(column, "z", MethodType.methodType(int.class));
		this.configJson = configJson;
	}

	@Override
	public WorldAccessor createWorldAccessor(WorldOptions worldOptions) throws MinecraftInterfaceException {
		return new MtmWorldAccessor(logicFor(worldOptions));
	}

	@Override
	public RecognisedVersion getRecognisedVersion() {
		return STRUCTURE_RULES;
	}

	@Override
	public void overrideFeatures(VersionFeatures.Builder features, WorldOptions worldOptions) throws MinecraftInterfaceException {
		Logic logic = logicFor(worldOptions);
		features.withOverride(FeatureKey.BIOME_LIST, BIOME_LIST);
		features.withOverride(FeatureKey.WORLD_SPAWN_ORACLE, new MtmSpawnOracle(logic));
		features.withWrapper(FeatureKey.VILLAGE_ICON_PRODUCER, villages -> new MtmVillageProducer(villages, logic));
		// The nether stays vanilla, and its biomes need Minecraft itself
		features.withOverride(FeatureKey.NETHER_FORTRESS_PRODUCER, new NoopProducer<>());
		features.withOverride(FeatureKey.BASTION_REMNANT_PRODUCER, new NoopProducer<>());
	}

	/**
	 * Terrain logic for a world. The accessors of all threads share it, because building the logic of a region is
	 * much more expensive than reading biomes from it. Only the newest world is kept.
	 */
	private synchronized Logic logicFor(WorldOptions worldOptions) throws MinecraftInterfaceException {
		long seed = worldOptions.getWorldSeed().getLong();
		String json = worldOptions.getGeneratorOptions();
		if (json == null || json.isEmpty()) {
			json = configJson;
		}
		if (current == null || current.seed != seed || !Objects.equals(current.settingsJson, json)) {
			try {
				Object settings = json == null ? defaultSettings : settingsFromJson.invoke(json);
				current = new Logic(seed, json, newLogic.invoke(seed, settings));
			} catch (Throwable e) {
				throw new MinecraftInterfaceException("Unable to create the MTM terrain logic", e);
			}
		}
		return current;
	}

	private int biomeId(String resourceId) {
		return biomeIds.computeIfAbsent(resourceId, id -> {
			Integer result = ModernBiomes.knownIds().get(id);
			if (result == null) {
				AmidstLogger.warn("Unknown biome '{}' is shown as the void", id);
				return UNKNOWN_BIOME;
			}
			return result;
		});
	}

	private static class Logic {
		private final long seed;
		private final String settingsJson;
		private final Object terrain;

		private Logic(long seed, String settingsJson, Object terrain) {
			this.seed = seed;
			this.settingsJson = settingsJson;
			this.terrain = terrain;
		}
	}

	private class MtmWorldAccessor implements WorldAccessor {
		private final Logic logic;
		private int[] buffer = new int[0];

		private MtmWorldAccessor(Logic logic) {
			this.logic = logic;
		}

		@Override
		public synchronized <T> T getBiomeData(Dimension dimension, int x, int y, int width, int height,
				boolean useQuarterResolution, Function<int[], T> biomeDataMapper) throws MinecraftInterfaceException {
			if (dimension != Dimension.OVERWORLD) {
				throw new MinecraftInterfaceException("MTM Worldgen only changes the overworld");
			}
			int size = width * height;
			if (buffer.length < size) {
				buffer = new int[size];
			}
			// Like the biome source of the mod, a quarter cell takes the biome of its lowest corner
			int shift = useQuarterResolution ? 2 : 0;
			try {
				for (int row = 0; row < height; row++) {
					for (int column = 0; column < width; column++) {
						double blockX = (double) ((x + column) << shift);
						double blockZ = (double) ((y + row) << shift);
						String biome = (String) sampleBiome.invoke(logic.terrain, blockX, blockZ);
						buffer[row * width + column] = biomeId(biome);
					}
				}
			} catch (Throwable e) {
				throw new MinecraftInterfaceException("Unable to read MTM biomes", e);
			}
			return biomeDataMapper.apply(buffer);
		}

		@Override
		public Set<Dimension> supportedDimensions() {
			return Collections.unmodifiableSet(EnumSet.of(Dimension.OVERWORLD));
		}
	}

	/** Leaves out the villages that the mod rejects for uneven ground around the middle of their chunk. */
	private class MtmVillageProducer extends WorldIconProducer<Void> {
		private final WorldIconProducer<Void> villages;
		private final Logic logic;

		private MtmVillageProducer(WorldIconProducer<Void> villages, Logic logic) {
			this.villages = villages;
			this.logic = logic;
		}

		@Override
		public void produce(CoordinatesInWorld corner, Consumer<WorldIcon> consumer, Void additionalData) {
			villages.produce(corner, icon -> {
				if (isFlat(icon.getCoordinates())) {
					consumer.accept(icon);
				}
			}, additionalData);
		}

		private boolean isFlat(CoordinatesInWorld coordinates) {
			double middleX = Math.floorDiv(coordinates.getX(), 16L) * 16 + 8;
			double middleZ = Math.floorDiv(coordinates.getY(), 16L) * 16 + 8;
			try {
				return (boolean) isVillageGround.invoke(logic.terrain, middleX, middleZ);
			} catch (Throwable e) {
				AmidstLogger.error(e, "Unable to check MTM village ground");
				return true;
			}
		}
	}

	/** The spawn search of the mod, run once when first needed. */
	private class MtmSpawnOracle implements WorldSpawnOracle {
		private final Logic logic;
		private volatile CoordinatesInWorld spawn;

		private MtmSpawnOracle(Logic logic) {
			this.logic = logic;
		}

		@Override
		public synchronized CoordinatesInWorld get() {
			if (spawn == null) {
				try {
					Object column = findSpawn.invoke(logic.terrain);
					spawn = CoordinatesInWorld.from((int) columnX.invoke(column), (int) columnZ.invoke(column));
				} catch (Throwable e) {
					AmidstLogger.error(e, "Unable to find the MTM world spawn");
					return null;
				}
			}
			return spawn;
		}
	}
}
