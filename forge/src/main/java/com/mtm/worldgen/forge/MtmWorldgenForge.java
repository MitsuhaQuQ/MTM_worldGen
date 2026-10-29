package com.mtm.worldgen.forge;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import com.mtm.worldgen.world.MtmGenerators;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

@Mod(MtmWorldgen.MOD_ID)
public final class MtmWorldgenForge {
    private static final DeferredRegister<ForgeWorldPreset> WORLD_TYPES =
            DeferredRegister.create(ForgeRegistries.Keys.WORLD_TYPES, MtmWorldgen.MOD_ID);

    static {
        WORLD_TYPES.register(MtmGenerators.WORLD_TYPE_ID.getPath(),
                () -> new ForgeWorldPreset((ForgeWorldPreset.IBasicChunkGeneratorFactory) MtmGenerators::create));
    }

    public MtmWorldgenForge() {
        MtmWorldgen.init();
        WORLD_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> MtmCommands.register(event.getDispatcher()));
    }
}
