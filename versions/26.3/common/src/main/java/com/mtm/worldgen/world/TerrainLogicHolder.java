package com.mtm.worldgen.world;

import com.mtm.worldgen.logic.TerrainLogic;

/** Added to RandomState by a mixin: the terrain logic of the world a seeded generator state belongs to. */
public interface TerrainLogicHolder {
    /** The terrain logic, or null if the state does not belong to an MTM generator. */
    TerrainLogic mtm$terrainLogic();
}
