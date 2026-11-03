package amidst.mojangapi.minecraftinterface.modern;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import amidst.documentation.Immutable;
import amidst.mojangapi.world.biome.Biome;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.biome.BiomeType;

/**
 * Since 1.18, biomes have no numeric ids anymore. Amidst keeps the ids of
 * 1.17 for the biomes that only got a new name, so that biome profiles still
 * apply to them, and gives the newer biomes ids after the last one of 1.17.
 */
@Immutable
public enum ModernBiomes {
	;

	/** Biomes that Amidst does not know, such as those of later versions, get ids from here on. */
	private static final int FIRST_UNKNOWN_ID = 1000;

	// @formatter:off
	private static final Object[][] KNOWN_BIOMES = {
		{ "ocean",                     0, BiomeType.OCEAN },
		{ "plains",                    1, BiomeType.PLAINS },
		{ "desert",                    2, BiomeType.PLAINS_FLAT },
		{ "windswept_hills",           3, BiomeType.MOUNTAINS },
		{ "forest",                    4, BiomeType.PLAINS },
		{ "taiga",                     5, BiomeType.PLAINS_TAIGA },
		{ "swamp",                     6, BiomeType.SWAMPLAND },
		{ "river",                     7, BiomeType.RIVER },
		{ "nether_wastes",             8, BiomeType.PLAINS },
		{ "the_end",                   9, BiomeType.PLAINS },
		{ "frozen_ocean",             10, BiomeType.OCEAN },
		{ "frozen_river",             11, BiomeType.RIVER },
		{ "snowy_plains",             12, BiomeType.PLAINS_FLAT },
		{ "mushroom_fields",          14, BiomeType.ISLAND },
		{ "beach",                    16, BiomeType.BEACH },
		{ "jungle",                   21, BiomeType.PLAINS },
		{ "sparse_jungle",            23, BiomeType.PLAINS },
		{ "deep_ocean",               24, BiomeType.DEEP_OCEAN },
		{ "stony_shore",              25, BiomeType.BEACH_CLIFFS },
		{ "snowy_beach",              26, BiomeType.BEACH },
		{ "birch_forest",             27, BiomeType.PLAINS },
		{ "dark_forest",              29, BiomeType.PLAINS },
		{ "snowy_taiga",              30, BiomeType.PLAINS_TAIGA },
		{ "old_growth_pine_taiga",    32, BiomeType.PLAINS_TAIGA },
		{ "windswept_forest",         34, BiomeType.MOUNTAINS },
		{ "savanna",                  35, BiomeType.PLAINS_FLAT },
		{ "savanna_plateau",          36, BiomeType.PLATEAU },
		{ "badlands",                 37, BiomeType.PLAINS },
		{ "wooded_badlands",          38, BiomeType.PLATEAU },
		{ "small_end_islands",        40, BiomeType.PLAINS },
		{ "end_midlands",             41, BiomeType.PLAINS },
		{ "end_highlands",            42, BiomeType.PLAINS },
		{ "end_barrens",              43, BiomeType.PLAINS },
		{ "warm_ocean",               44, BiomeType.OCEAN },
		{ "lukewarm_ocean",           45, BiomeType.OCEAN },
		{ "cold_ocean",               46, BiomeType.OCEAN },
		{ "deep_lukewarm_ocean",      48, BiomeType.DEEP_OCEAN },
		{ "deep_cold_ocean",          49, BiomeType.DEEP_OCEAN },
		{ "deep_frozen_ocean",        50, BiomeType.DEEP_OCEAN },
		{ "the_void",                127, BiomeType.PLAINS },
		{ "sunflower_plains",        129, BiomeType.PLAINS },
		{ "windswept_gravelly_hills",131, BiomeType.MOUNTAINS },
		{ "flower_forest",           132, BiomeType.PLAINS },
		{ "ice_spikes",              140, BiomeType.PLAINS_FLAT },
		{ "old_growth_birch_forest", 155, BiomeType.PLAINS },
		{ "old_growth_spruce_taiga", 160, BiomeType.PLAINS_TAIGA },
		{ "windswept_savanna",       163, BiomeType.MOUNTAINS },
		{ "eroded_badlands",         165, BiomeType.PLAINS },
		{ "bamboo_jungle",           168, BiomeType.PLAINS },
		{ "soul_sand_valley",        170, BiomeType.PLAINS },
		{ "crimson_forest",          171, BiomeType.PLAINS },
		{ "warped_forest",           172, BiomeType.PLAINS },
		{ "basalt_deltas",           173, BiomeType.PLAINS },
		// 1.18
		{ "meadow",                  174, BiomeType.PLATEAU },
		{ "grove",                   175, BiomeType.HILLS },
		{ "snowy_slopes",            176, BiomeType.HILLS },
		{ "frozen_peaks",            177, BiomeType.MOUNTAINS },
		{ "jagged_peaks",            178, BiomeType.MOUNTAINS },
		{ "stony_peaks",             179, BiomeType.MOUNTAINS },
		{ "dripstone_caves",         180, BiomeType.PLAINS },
		{ "lush_caves",              181, BiomeType.PLAINS },
		// 1.19
		{ "deep_dark",               182, BiomeType.PLAINS },
		{ "mangrove_swamp",          183, BiomeType.SWAMPLAND },
		// 1.20
		{ "cherry_grove",            184, BiomeType.PLATEAU },
		// 1.21.4
		{ "pale_garden",             185, BiomeType.PLAINS },
		// 26.x
		{ "dappled_forest",          186, BiomeType.PLAINS },
		{ "sulfur_caves",            187, BiomeType.PLAINS },
	};
	// @formatter:on

	private static final Map<String, Integer> KNOWN_IDS = createKnownIds();
	private static final Map<String, BiomeType> KNOWN_TYPES = createKnownTypes();

	private static Map<String, Integer> createKnownIds() {
		Map<String, Integer> result = new HashMap<>();
		for (Object[] biome : KNOWN_BIOMES) {
			result.put("minecraft:" + biome[0], (Integer) biome[1]);
		}
		return Collections.unmodifiableMap(result);
	}

	private static Map<String, BiomeType> createKnownTypes() {
		Map<String, BiomeType> result = new HashMap<>();
		for (Object[] biome : KNOWN_BIOMES) {
			result.put("minecraft:" + biome[0], (BiomeType) biome[2]);
		}
		return Collections.unmodifiableMap(result);
	}

	/** The ids of the biomes Amidst knows, by key such as minecraft:meadow. */
	public static Map<String, Integer> knownIds() {
		return KNOWN_IDS;
	}

	/**
	 * Gives every biome of the registry an id. Biomes that Amidst does not
	 * know get ids in the order of their sorted keys, so they keep their ids
	 * between runs of the same Minecraft version.
	 */
	public static Map<String, Integer> assignIds(Collection<String> keys) {
		Map<String, Integer> result = new HashMap<>();
		List<String> unknown = new ArrayList<>();
		for (String key : keys) {
			Integer id = KNOWN_IDS.get(key);
			if (id != null) {
				result.put(key, id);
			} else {
				unknown.add(key);
			}
		}
		Collections.sort(unknown);
		for (int i = 0; i < unknown.size(); i++) {
			result.put(unknown.get(i), FIRST_UNKNOWN_ID + i);
		}
		return Collections.unmodifiableMap(result);
	}

	public static BiomeList createBiomeList(Map<String, Integer> ids) {
		BiomeList result = new BiomeList();
		ids.forEach((key, id) -> result.add(new Biome(id, displayName(key), KNOWN_TYPES.getOrDefault(key, BiomeType.PLAINS))));
		return result;
	}

	/**
	 * Turns a key like minecraft:old_growth_pine_taiga into Old Growth Pine
	 * Taiga, like the game names it in English.
	 */
	public static String displayName(String key) {
		String path = key.substring(key.indexOf(':') + 1);
		StringBuilder result = new StringBuilder();
		for (String word : path.split("[_/]")) {
			if (word.isEmpty()) {
				continue;
			}
			if (result.length() > 0) {
				result.append(' ');
			}
			result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return result.toString();
	}
}
