package com.mtm.worldgen.webmap;

import java.util.Set;

/**
 * What a block means to the map, from its name alone. Natural ground ends the scan down a column; water, trees and
 * snow layers on it are noted on the way; anything else, which is how buildings, roads, rails and crops are told
 * apart, is passed through as if it were air, so the map shows the land under them.
 */
enum BlockClass {
    /** Air, plants and anything not natural: the scan goes on below. */
    PASS(false),
    WATER(false),
    /** Ice that forms on water, which is water to the map: frozen rivers and lakes stay rivers and lakes. */
    FROZEN(false),
    LAVA(false),
    /** Logs and leaves: forest over the ground below. */
    TREE(false),
    /** A layer of snow on the ground below. */
    SNOW_LAYER(false),
    GRASS(true),
    SOIL(true),
    SAND(true),
    GRAVEL(true),
    ROCK(true),
    SNOW(true),
    ICE(true),
    TERRACOTTA(true);

    private static final Set<String> WATER_NAMES = Set.of("water", "bubble_column", "kelp", "kelp_plant", "seagrass", "tall_seagrass");
    private static final Set<String> GRASS_NAMES = Set.of("grass_block", "moss_block", "mycelium", "podzol");
    private static final Set<String> SOIL_NAMES = Set.of("dirt", "coarse_dirt", "rooted_dirt", "mud", "muddy_mangrove_roots", "clay");
    private static final Set<String> SAND_NAMES = Set.of("sand", "red_sand", "suspicious_sand", "sandstone", "red_sandstone");
    private static final Set<String> GRAVEL_NAMES = Set.of("gravel", "suspicious_gravel");
    private static final Set<String> SNOW_NAMES = Set.of("snow_block", "powder_snow");
    private static final Set<String> ICE_NAMES = Set.of("packed_ice", "blue_ice");
    private static final Set<String> ROCK_NAMES = Set.of("stone", "deepslate", "granite", "diorite", "andesite", "tuff", "calcite",
            "dripstone_block", "smooth_basalt", "basalt", "bedrock", "magma_block", "obsidian", "amethyst_block",
            "budding_amethyst", "infested_stone", "infested_deepslate", "raw_iron_block", "raw_copper_block");

    /** Whether the block is natural ground, which ends the scan of a column. */
    final boolean ground;

    BlockClass(boolean ground) {
        this.ground = ground;
    }

    static BlockClass of(String id) {
        String name = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (WATER_NAMES.contains(name)) {
            return WATER;
        }
        if (name.equals("ice") || name.equals("frosted_ice")) {
            return FROZEN;
        }
        if (name.equals("lava")) {
            return LAVA;
        }
        if (name.endsWith("_leaves") || name.endsWith("_log") || name.endsWith("_wood") || name.equals("mangrove_roots")) {
            return TREE;
        }
        if (name.equals("snow")) {
            return SNOW_LAYER;
        }
        if (GRASS_NAMES.contains(name)) {
            return GRASS;
        }
        if (SOIL_NAMES.contains(name)) {
            return SOIL;
        }
        if (SAND_NAMES.contains(name)) {
            return SAND;
        }
        if (GRAVEL_NAMES.contains(name)) {
            return GRAVEL;
        }
        if (SNOW_NAMES.contains(name)) {
            return SNOW;
        }
        if (ICE_NAMES.contains(name)) {
            return ICE;
        }
        if (name.equals("terracotta") || name.endsWith("_terracotta") && !name.endsWith("glazed_terracotta")) {
            return TERRACOTTA;
        }
        // Reefs are ground under the sea; the corals growing on them are like seagrass
        if (name.endsWith("_coral") || name.endsWith("_coral_fan") || name.endsWith("_coral_wall_fan") || name.equals("sea_pickle")) {
            return WATER;
        }
        if (ROCK_NAMES.contains(name) || name.endsWith("_ore") || name.endsWith("_coral_block")) {
            return ROCK;
        }
        return PASS;
    }
}
