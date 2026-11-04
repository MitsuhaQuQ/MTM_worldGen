package com.mtm.worldgen.forge;

import com.mojang.serialization.MapCodec;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import com.mtm.worldgen.gametest.MtmGameTests;
import com.mtm.worldgen.world.MtmBiomeSource;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.TerrainCaves;
import com.mtm.worldgen.world.TerrainClimate;
import com.mtm.worldgen.world.TerrainCondition;
import com.mtm.worldgen.world.TerrainDensity;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;

import java.util.function.Consumer;

@Mod(MtmWorldgen.MOD_ID)
public final class MtmWorldgenForge {
    // Vanilla registries are frozen before mod construction on Forge, so registration must be deferred
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends MaterialCondition>> MATERIAL_CONDITIONS =
            DeferredRegister.create(Registries.MATERIAL_CONDITION_TYPE, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<MapCodec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, MtmWorldgen.MOD_ID);

    static {
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.TERRAIN_DENSITY_ID.getPath(), () -> TerrainDensity.CODEC);
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.CLIMATE_DENSITY_ID.getPath(), () -> TerrainClimate.CODEC);
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.CAVES_DENSITY_ID.getPath(), () -> TerrainCaves.CODEC);
        MATERIAL_CONDITIONS.register(MtmGenerators.TERRAIN_CONDITION_ID.getPath(), () -> TerrainCondition.CODEC);
        BIOME_SOURCES.register(MtmGenerators.BIOME_SOURCE_ID.getPath(), () -> MtmBiomeSource.CODEC);
        MtmGameTests.register((name, test) -> TEST_FUNCTIONS.register(name, () -> test));
    }

    public MtmWorldgenForge(FMLJavaModLoadingContext context) {
        MtmWorldgen.init();
        BusGroup modBus = context.getModBusGroup();
        DENSITY_FUNCTION_TYPES.register(modBus);
        MATERIAL_CONDITIONS.register(modBus);
        BIOME_SOURCES.register(modBus);
        TEST_FUNCTIONS.register(modBus);
        RegisterCommandsEvent.BUS.addListener(event -> MtmCommands.register(event.getDispatcher()));
    }
}
