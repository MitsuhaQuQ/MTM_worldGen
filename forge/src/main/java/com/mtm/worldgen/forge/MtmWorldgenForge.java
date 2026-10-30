package com.mtm.worldgen.forge;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import com.mtm.worldgen.gametest.MtmGameTests;
import com.mtm.worldgen.world.TerrainCondition;
import com.mtm.worldgen.world.MtmBiomeSource;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.TerrainClimate;
import com.mtm.worldgen.world.TerrainDensity;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(MtmWorldgen.MOD_ID)
public final class MtmWorldgenForge {
    // Vanilla registries are frozen before mod construction on Forge, so registration must be deferred
    private static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registry.DENSITY_FUNCTION_TYPE_REGISTRY, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<Codec<? extends SurfaceRules.ConditionSource>> SURFACE_CONDITIONS =
            DeferredRegister.create(Registry.CONDITION_REGISTRY, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registry.BIOME_SOURCE_REGISTRY, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<ForgeWorldPreset> WORLD_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.WORLD_TYPES, MtmWorldgen.MOD_ID);

    static {
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.TERRAIN_DENSITY_ID.getPath(), () -> TerrainDensity.CODEC);
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.CLIMATE_DENSITY_ID.getPath(), () -> TerrainClimate.CODEC);
        SURFACE_CONDITIONS.register(MtmGenerators.TERRAIN_CONDITION_ID.getPath(), () -> TerrainCondition.CODEC);
        BIOME_SOURCES.register(MtmGenerators.BIOME_SOURCE_ID.getPath(), () -> MtmBiomeSource.CODEC);
        WORLD_TYPES.register(MtmGenerators.WORLD_TYPE_ID.getPath(), MtmForgeWorldPreset::new);
    }

    public MtmWorldgenForge() {
        MtmWorldgen.init();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DENSITY_FUNCTION_TYPES.register(modBus);
        SURFACE_CONDITIONS.register(modBus);
        BIOME_SOURCES.register(modBus);
        WORLD_TYPES.register(modBus);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(MtmGameTests.class));
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> MtmCommands.register(event.getDispatcher()));
    }
}
