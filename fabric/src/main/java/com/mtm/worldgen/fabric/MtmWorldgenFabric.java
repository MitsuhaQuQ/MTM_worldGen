package com.mtm.worldgen.fabric;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import com.mtm.worldgen.world.TerrainCondition;
import com.mtm.worldgen.world.MtmBiomeSource;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.TerrainClimate;
import com.mtm.worldgen.world.TerrainDensity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public final class MtmWorldgenFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MtmWorldgen.init();
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, MtmGenerators.TERRAIN_DENSITY_ID, TerrainDensity.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, MtmGenerators.CLIMATE_DENSITY_ID, TerrainClimate.CODEC);
        Registry.register(BuiltInRegistries.MATERIAL_CONDITION, MtmGenerators.TERRAIN_CONDITION_ID, TerrainCondition.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, MtmGenerators.BIOME_SOURCE_ID, MtmBiomeSource.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> MtmCommands.register(dispatcher));
    }
}
