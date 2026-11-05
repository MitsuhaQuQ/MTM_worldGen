package com.mtm.worldgen.world;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.platform.Platform;
import com.mtm.worldgen.webmap.WebMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/** Runs the web map of an MTM world's overworld on the server that has it, when the map's config turns it on. */
public final class MtmWebMap {
    private static final WebMap.Log LOG = new WebMap.Log() {
        @Override
        public void info(String message) {
            MtmWorldgen.LOGGER.info(message);
        }

        @Override
        public void warn(String message, Throwable error) {
            MtmWorldgen.LOGGER.warn(message, error);
        }
    };

    private MtmWebMap() {
    }

    /** Starts the map of an MTM overworld that has just loaded. */
    public static void start(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        WebMap.start(root, ForeignChunks.regionFolder(root), Platform.get().configDir(),
                level.getServer().getWorldData().getLevelName(), LOG);
    }

    public static void stop() {
        WebMap.stop();
    }
}
