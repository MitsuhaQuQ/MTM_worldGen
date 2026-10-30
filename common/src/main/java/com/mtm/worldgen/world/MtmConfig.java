package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.logic.WorldgenSettings;
import com.mtm.worldgen.platform.Platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The global config file, config/mtm_worldgen.json. It only applies to worlds created afterwards: a new world
 * stores its settings in level.dat, so editing this file never changes existing terrain.
 */
public final class MtmConfig {
    private static final String FILE_NAME = "mtm_worldgen.json";

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
                Files.writeString(file, WorldgenSettings.DEFAULT.toJson(), StandardCharsets.UTF_8);
                MtmWorldgen.LOGGER.info("Created default config at {}", file);
                return WorldgenSettings.DEFAULT;
            }
            return WorldgenSettings.fromJson(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot access " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid config " + file + ": " + e.getMessage(), e);
        }
    }
}
