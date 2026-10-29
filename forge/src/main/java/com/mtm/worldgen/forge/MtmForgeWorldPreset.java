package com.mtm.worldgen.forge;

import com.mtm.worldgen.world.MtmGenerators;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraftforge.common.world.ForgeWorldPreset;

/**
 * Forge world type. Overrides settings creation as a whole, because the default pairs the generator with the
 * vanilla overworld height. Also used for level-type=mtm_worldgen:realistic in server.properties.
 */
public final class MtmForgeWorldPreset extends ForgeWorldPreset {
    public MtmForgeWorldPreset() {
        super((IBasicChunkGeneratorFactory) MtmGenerators::create);
    }

    @Override
    public WorldGenSettings createSettings(RegistryAccess access, long seed, boolean generateFeatures, boolean bonusChest,
                                           String generatorSettings) {
        return MtmGenerators.createSettings(access, seed, generateFeatures, bonusChest);
    }
}
