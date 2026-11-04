package com.mtm.worldgen.gametest;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * A block column a generator computes without generating chunks. Its type moves between Minecraft versions, which then
 * only need a file like this of their own.
 */
record BaseColumn(NoiseColumn column) {
    static BaseColumn of(ChunkGenerator generator, int x, int z, LevelHeightAccessor height, RandomState random) {
        return new BaseColumn(generator.getBaseColumn(x, z, height, random));
    }

    BlockState getBlock(int y) {
        return column.getBlock(y);
    }
}
