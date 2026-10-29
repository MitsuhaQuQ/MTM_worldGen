package com.mtm.worldgen.fabric;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.TerrainDensity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.core.Registry;

public final class MtmWorldgenFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MtmWorldgen.init();
        Registry.register(Registry.DENSITY_FUNCTION_TYPES, MtmGenerators.TERRAIN_DENSITY_ID, TerrainDensity.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> MtmCommands.register(dispatcher));
    }
}
