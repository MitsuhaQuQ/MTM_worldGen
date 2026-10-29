package com.mtm.worldgen.fabric;

import com.mtm.worldgen.MtmWorldgen;
import com.mtm.worldgen.command.MtmCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;

public final class MtmWorldgenFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MtmWorldgen.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> MtmCommands.register(dispatcher));
    }
}
