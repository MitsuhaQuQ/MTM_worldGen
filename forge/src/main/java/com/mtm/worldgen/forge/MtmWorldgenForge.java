package com.mtm.worldgen.forge;

import com.mtm.worldgen.MtmWorldgen;
import net.minecraftforge.fml.common.Mod;

@Mod(MtmWorldgen.MOD_ID)
public final class MtmWorldgenForge {
    public MtmWorldgenForge() {
        MtmWorldgen.init();
    }
}
