package com.mtm.worldgen.fabric.client;

import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.client.gui.screens.worldselection.WorldPreset;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenSettings;

/** Create World screen entry. Fabric has no world type API in 1.18.2, so we extend the vanilla list. */
public final class MtmWorldPreset extends WorldPreset {
    private MtmWorldPreset() {
        super(MtmGenerators.WORLD_TYPE_ID.getNamespace() + "." + MtmGenerators.WORLD_TYPE_ID.getPath());
    }

    public static void register() {
        PRESETS.add(new MtmWorldPreset());
    }

    /** Overridden as a whole, because vanilla would pair the generator with the default overworld height. */
    @Override
    public WorldGenSettings create(RegistryAccess access, long seed, boolean generateFeatures, boolean bonusChest) {
        return MtmGenerators.createSettings(access, seed, generateFeatures, bonusChest);
    }

    @Override
    protected ChunkGenerator generator(RegistryAccess access, long seed) {
        return MtmGenerators.create(access, seed);
    }
}
