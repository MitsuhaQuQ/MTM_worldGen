package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.platform.Platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The global config file, config/mtm_worldgen.json. It only applies to worlds created afterwards: a new world
 * stores its settings in level.dat, so editing this file never changes existing terrain.
 * <p>
 * The file is written with a comment above every group and setting, taken from the documentation of
 * {@link WorldgenSettings} (the resource {@value #DOCS}, generated from its source). A file without comments, as older
 * versions wrote it, is written again with them, keeping its values.
 */
public final class MtmConfig {
    private static final String FILE_NAME = "mtm_worldgen.json";
    private static final String DOCS = "/mtm_worldgen/settings_docs.properties";
    private static final String HEADER = "Realistic Landforms settings. They only apply to worlds created after a change: a new "
            + "world locks them into its level.dat, and the terrain of existing worlds never changes. Delete this file to "
            + "have it written again with the current defaults; settings missing from it take their defaults, and values "
            + "out of range stop the world from being created, with the reason in the log. Lines starting with // are "
            + "comments.\n\n"
            + "Heights said to be on the 300 block scale are designed for a 300 block surface and scale with "
            + "general.maxSurfaceHeight, so mountains, snow lines and ores keep their proportions at any world height. "
            + "Other sizes are in blocks, 1 block = 1 meter. The scale group makes landforms larger or smaller; "
            + "general.logicRange instead lets the terrain logic look further around each place, for longer rivers, "
            + "wider river plains and smoother region joins with the same proportions, at the cost of speed and memory.";

    private MtmConfig() {
    }

    /**
     * Reads the config file, creating it with the defaults if missing.
     *
     * @throws IllegalStateException if the file is unreadable or invalid, rather than silently creating a world
     *                               with different settings than the user asked for
     */
    public static WorldgenSettings load() {
        Path file = Platform.get().configDir().resolve(FILE_NAME);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, commented(WorldgenSettings.DEFAULT), StandardCharsets.UTF_8);
                MtmWorldgen.LOGGER.info("Created default config at {}", file);
                return WorldgenSettings.DEFAULT;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            WorldgenSettings settings = WorldgenSettings.fromJson(text);
            if (!text.contains("//")) {
                Files.writeString(file, commented(settings), StandardCharsets.UTF_8);
                MtmWorldgen.LOGGER.info("Added comments to {}, keeping its values", file);
            }
            return settings;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot access " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid config " + file + ": " + e.getMessage(), e);
        }
    }

    /** The settings as the config file holds them, with their documentation as comments. */
    static String commented(WorldgenSettings settings) {
        return settings.toCommentedJson(HEADER, docs());
    }

    /** Documentation of each group and setting, by key; empty if the resource is missing. */
    static Map<String, String> docs() {
        Map<String, String> docs = new LinkedHashMap<>();
        try (InputStream in = MtmConfig.class.getResourceAsStream(DOCS)) {
            if (in == null) {
                return docs;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int split = line.indexOf('=');
                if (!line.startsWith("#") && split > 0) {
                    docs.put(line.substring(0, split), line.substring(split + 1).replace("\\\\", "\\"));
                }
            }
        } catch (IOException e) {
            MtmWorldgen.LOGGER.warn("Cannot read the settings documentation", e);
        }
        return docs;
    }
}
