package com.mtm.worldgen.fabric;

import com.mtm.worldgen.MtmWorldgen;
import net.fabricmc.api.ModInitializer;

public final class MtmWorldgenFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        MtmWorldgen.init();
    }
}
