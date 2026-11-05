package com.mtm.worldgen.neoforge;

import com.mojang.serialization.Codec;
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
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(MtmWorldgen.MOD_ID)
public final class MtmWorldgenNeoForge {
    // Vanilla registries are frozen before mod construction, so registration must be deferred
    private static final DeferredRegister<Codec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<Codec<? extends SurfaceRules.ConditionSource>> SURFACE_CONDITIONS =
            DeferredRegister.create(Registries.MATERIAL_CONDITION, MtmWorldgen.MOD_ID);
    private static final DeferredRegister<Codec<? extends BiomeSource>> BIOME_SOURCES =
            DeferredRegister.create(Registries.BIOME_SOURCE, MtmWorldgen.MOD_ID);

    static {
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.TERRAIN_DENSITY_ID.getPath(), () -> TerrainDensity.DISPATCH_CODEC.codec());
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.CLIMATE_DENSITY_ID.getPath(), () -> TerrainClimate.DISPATCH_CODEC.codec());
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.CAVES_DENSITY_ID.getPath(), () -> TerrainCaves.DISPATCH_CODEC.codec());
        SURFACE_CONDITIONS.register(MtmGenerators.TERRAIN_CONDITION_ID.getPath(), () -> TerrainCondition.DISPATCH_CODEC.codec());
        BIOME_SOURCES.register(MtmGenerators.BIOME_SOURCE_ID.getPath(), () -> MtmBiomeSource.DISPATCH_CODEC);
    }

    public MtmWorldgenNeoForge(IEventBus modBus) {
        MtmWorldgen.init();
        DENSITY_FUNCTION_TYPES.register(modBus);
        SURFACE_CONDITIONS.register(modBus);
        BIOME_SOURCES.register(modBus);
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(MtmGameTests.class));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> MtmCommands.register(event.getDispatcher()));
    }
}
