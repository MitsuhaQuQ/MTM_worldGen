package com.mtm.worldgen.forge;

import com.mojang.serialization.Codec;
import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import com.mtm.worldgen.world.MtmGenerators;
import com.mtm.worldgen.world.TerrainDensity;
import net.minecraft.core.Registry;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegisterCommandsEvent;
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
    private static final DeferredRegister<ForgeWorldPreset> WORLD_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.WORLD_TYPES, MtmWorldgen.MOD_ID);

    static {
        DENSITY_FUNCTION_TYPES.register(MtmGenerators.TERRAIN_DENSITY_ID.getPath(), () -> TerrainDensity.CODEC);
        WORLD_TYPES.register(MtmGenerators.WORLD_TYPE_ID.getPath(),
                () -> new ForgeWorldPreset((ForgeWorldPreset.IBasicChunkGeneratorFactory) MtmGenerators::create));
    }

    public MtmWorldgenForge() {
        MtmWorldgen.init();
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DENSITY_FUNCTION_TYPES.register(modBus);
        WORLD_TYPES.register(modBus);
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> MtmCommands.register(event.getDispatcher()));
    }
}
