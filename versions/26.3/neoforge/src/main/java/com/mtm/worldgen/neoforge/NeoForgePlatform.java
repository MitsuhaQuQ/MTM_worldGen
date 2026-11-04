package com.mtm.worldgen.neoforge;

import com.mtm.worldgen.platform.Platform;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class NeoForgePlatform implements Platform {
    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }

    @Override
    public boolean recompilesMinecraft() {
        // NeoForge's development environment builds Minecraft from NeoForm's decompiled sources
        return isDevelopmentEnvironment();
    }
}
