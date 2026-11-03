package amidst.mojangapi.file.nbt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.querz.nbt.tag.ByteTag;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.IntArrayTag;
import net.querz.nbt.tag.StringTag;
import net.querz.nbt.tag.Tag;

import amidst.documentation.Immutable;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.parsing.FormatException;

@Immutable
public class LevelDatNbt {
	private static final String MTM_BIOME_SOURCE = "mtm_worldgen:terrain";

	public static LevelDatNbt from(Path path) throws IOException, FormatException {
		try {
			CompoundTag dataTag = readDataTag(NBTUtils.readTagFromFile(path));
			CompoundTag worldGenSettings = readWorldGenSettings(dataTag, path);
			long seed = readRandomSeed(dataTag, worldGenSettings);
			CoordinatesInWorld worldSpawn = readWorldSpawn(dataTag);
			WorldType worldType = readWorldType(dataTag, worldGenSettings);
			String generatorOptions = readGeneratorOptions(dataTag, worldType);
			boolean hasPlayer = hasPlayerTag(dataTag);
			return new LevelDatNbt(seed, worldSpawn, worldType, generatorOptions, hasPlayer);
		} catch (NullPointerException | ClassCastException e) {
			throw new FormatException("cannot read level.dat: " + path, e);
		}
	}

	private static CompoundTag readDataTag(CompoundTag root) throws IOException {
		return root.get(NBTTagKeys.TAG_KEY_DATA, CompoundTag.class);
	}

	/**
	 * The world generation settings are in level.dat since Minecraft 1.16,
	 * and in a file of their own since Minecraft 26.1. Older worlds have none.
	 */
	private static CompoundTag readWorldGenSettings(CompoundTag dataTag, Path levelDat) throws IOException {
		CompoundTag result = dataTag.get(NBTTagKeys.TAG_KEY_WORLD_GEN_SETTINGS, CompoundTag.class);
		if (result != null) {
			return result;
		}
		Path file = levelDat.resolveSibling(NBTTagKeys.WORLD_GEN_SETTINGS_FILE);
		if (Files.isRegularFile(file)) {
			return NBTUtils.readTagFromFile(file).get(NBTTagKeys.TAG_KEY_DATA_LOWER_CASE, CompoundTag.class);
		}
		return null;
	}

	private static long readRandomSeed(CompoundTag dataTag, CompoundTag worldGenSettings) {
		Tag<?> randomSeed = dataTag.get(NBTTagKeys.TAG_KEY_RANDOM_SEED);
		if (randomSeed != null) {
			return NBTUtils.getLongValue(randomSeed);
		}
		return NBTUtils.getLongValue(worldGenSettings.get(NBTTagKeys.TAG_KEY_SEED));
	}

	private static CoordinatesInWorld readWorldSpawn(CompoundTag dataTag) {
		if (!dataTag.containsKey(NBTTagKeys.TAG_KEY_SPAWN_X)) {
			// Minecraft 26.1 format
			int[] position = ((IntArrayTag) NBTUtils.getNestedTag(dataTag, NBTTagKeys.TAG_KEY_SPAWN, NBTTagKeys.TAG_KEY_SPAWN_POS)).getValue();
			return CoordinatesInWorld.from(position[0], position[2]);
		}
		return CoordinatesInWorld.from(readSpawnX(dataTag), readSpawnZ(dataTag));
	}

	private static long readSpawnX(CompoundTag dataTag) {
		return NBTUtils.getLongValue(dataTag.get(NBTTagKeys.TAG_KEY_SPAWN_X));
	}

	private static long readSpawnZ(CompoundTag dataTag) {
		return NBTUtils.getLongValue(dataTag.get(NBTTagKeys.TAG_KEY_SPAWN_Z));
	}

	private static WorldType readWorldType(CompoundTag dataTag, CompoundTag worldGenSettings) {
		WorldType heuristicWorldType = tryGuessWorldType(worldGenSettings);
		if (heuristicWorldType != null) {
			return heuristicWorldType;
		} else if (hasGeneratorName(dataTag)) {
			return WorldType.from(readGeneratorName(dataTag));
		} else {
			return WorldType.DEFAULT;
		}
	}

	private static boolean hasGeneratorName(CompoundTag dataTag) {
		return dataTag.containsKey(NBTTagKeys.TAG_KEY_GENERATOR_NAME);
	}

	private static String readGeneratorOptions(CompoundTag dataTag, WorldType worldType) {
		if (worldType == WorldType.CUSTOMIZED) {
			return readGeneratorOptions(dataTag);
		} else {
			return readMtmSettings(dataTag);
		}
	}

	// MTM Worldgen locks the settings of a world into its biome source when the world is created
	private static String readMtmSettings(CompoundTag dataTag) {
		Tag<?> biomeSource = NBTUtils.getNestedTag(dataTag,
			"WorldGenSettings", "dimensions", "minecraft:overworld", "generator", "biome_source");
		Tag<?> type = NBTUtils.getNestedTag(biomeSource, "type");
		Tag<?> settings = NBTUtils.getNestedTag(biomeSource, "settings");
		if (new StringTag(MTM_BIOME_SOURCE).equals(type) && settings instanceof StringTag) {
			return ((StringTag) settings).getValue();
		}
		return "";
	}

	private static String readGeneratorName(CompoundTag dataTag) {
		return dataTag.get(NBTTagKeys.TAG_KEY_GENERATOR_NAME, StringTag.class).getValue();
	}

	// Try to guess world type from Minecraft 1.16 generator settings
	private static WorldType tryGuessWorldType(CompoundTag worldGenSettings) {
		// Be careful when handling null values, we don't want to trigger unwanted errors
		Tag<?> generator = NBTUtils.getNestedTag(worldGenSettings,
			"dimensions", "minecraft:overworld", "generator");

		Tag<?> generatorType = NBTUtils.getNestedTag(generator, "type");
		if (new StringTag("minecraft:flat").equals(generatorType)) {
			return WorldType.FLAT;
		}

		if (new StringTag("minecraft:noise").equals(generatorType)) {
			Tag<?> settings = NBTUtils.getNestedTag(generator, "settings");
			if (new StringTag("minecraft:amplified").equals(settings)) {
				return WorldType.AMPLIFIED;
			}
			if (new StringTag("minecraft:large_biomes").equals(settings)) {
				return WorldType.LARGE_BIOMES;
			}
			Tag<?> largeBiomes = NBTUtils.getNestedTag(generator, "biome_source", "large_biomes");
			if (new ByteTag(true).equals(largeBiomes)) {
				return WorldType.LARGE_BIOMES;
			} else {
				return WorldType.DEFAULT;
			}
		}

		return null;
	}

	private static String readGeneratorOptions(CompoundTag dataTag) {
		return dataTag.get(NBTTagKeys.TAG_KEY_GENERATOR_OPTIONS, StringTag.class).getValue();
	}

	private static boolean hasPlayerTag(CompoundTag dataTag) {
		return dataTag.containsKey(NBTTagKeys.TAG_KEY_PLAYER);
	}

	private final long seed;
	private final CoordinatesInWorld worldSpawn;
	private final WorldType worldType;
	private final String generatorOptions;
	private final boolean hasPlayer;

	public LevelDatNbt(
			long seed,
			CoordinatesInWorld worldSpawn,
			WorldType worldType,
			String generatorOptions,
			boolean hasPlayer) {
		this.seed = seed;
		this.worldSpawn = worldSpawn;
		this.worldType = worldType;
		this.generatorOptions = generatorOptions;
		this.hasPlayer = hasPlayer;
	}

	public long getSeed() {
		return seed;
	}

	public CoordinatesInWorld getWorldSpawn() {
		return worldSpawn;
	}

	public WorldType getWorldType() {
		return worldType;
	}

	public String getGeneratorOptions() {
		return generatorOptions;
	}

	public boolean hasPlayer() {
		return hasPlayer;
	}
}
