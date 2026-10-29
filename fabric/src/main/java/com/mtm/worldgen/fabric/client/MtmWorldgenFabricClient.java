package com.mtm.worldgen.fabric.client;

import net.fabricmc.api.ClientModInitializer;

public final class MtmWorldgenFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MtmWorldPreset.register();
    }
}
