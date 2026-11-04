package com.mtm.worldgen.logic;

import java.util.Arrays;

/**
 * Cached large-scale terrain logic for one logic region: coarse grid values for every cell inside it.
 * Immutable once built, so it can be shared freely between worldgen threads.
 */
public final class LogicRegion {
    /** Bumped whenever generation rules change; stored with saved regions to blend algorithm versions. */
    public static final int ALGORITHM_VERSION = 1;

    private final int regionX;
    private final int regionZ;
    private final int cells;
    private final float[] heights;
    private final float[] coastDistances;

    LogicRegion(int regionX, int regionZ, int cells, float[] heights, float[] coastDistances) {
        if (heights.length != cells * cells || coastDistances.length != cells * cells) {
            throw new IllegalArgumentException("cell arrays must hold cells * cells values");
        }
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.cells = cells;
        this.heights = heights;
        this.coastDistances = coastDistances;
    }

    public int regionX() {
        return regionX;
    }

    public int regionZ() {
        return regionZ;
    }

    public int cells() {
        return cells;
    }

    /** Coarse surface height at a cell center, by cell index local to this region. */
    public float cellHeight(int localX, int localZ) {
        return heights[localZ * cells + localX];
    }

    /** Estimated distance to the coastline at a cell center in blocks: positive inland, negative offshore. */
    public float cellCoastDistance(int localX, int localZ) {
        return coastDistances[localZ * cells + localX];
    }

    boolean contentEquals(LogicRegion other) {
        return regionX == other.regionX && regionZ == other.regionZ && cells == other.cells
                && Arrays.equals(heights, other.heights) && Arrays.equals(coastDistances, other.coastDistances);
    }
}
