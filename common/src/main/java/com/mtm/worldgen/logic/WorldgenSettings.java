package com.mtm.worldgen.logic;

/**
 * Terrain logic parameters. Will be loaded from the world's locked config.json; until then
 * {@link #DEFAULT} holds the values from the design document.
 *
 * @param seaLevel                 vanilla sea level field; the top water block sits at seaLevel - 1 (y=62)
 * @param maxSurfaceHeight         highest surface height terrain may reach
 * @param regionSize               logic region edge length in blocks
 * @param cellSize                 coarse grid cell edge length in blocks
 * @param lookAhead                radius in blocks read around a region when computing it
 * @param oceanRatio               target fraction of the surface covered by ocean
 * @param mountainRangeProbability chance that a mountain site is part of a range rather than an isolated peak
 * @param plainsSlope              maximum grade on plains, percent (height difference / horizontal distance * 100)
 * @param hillsSlope               maximum grade on hills, percent
 * @param plateauSlope             maximum grade on plateau edges, percent
 * @param mountainSlope            maximum grade on mountains, percent
 */
public record WorldgenSettings(int seaLevel, int maxSurfaceHeight, int regionSize, int cellSize, int lookAhead,
                               double oceanRatio, double mountainRangeProbability,
                               double plainsSlope, double hillsSlope, double plateauSlope, double mountainSlope) {
    public static final WorldgenSettings DEFAULT =
            new WorldgenSettings(63, 600, 4096, 64, 4096, 0.30, 0.8, 5.0, 25.0, 40.0, 150.0);

    public WorldgenSettings {
        if (cellSize <= 0 || regionSize % cellSize != 0) {
            throw new IllegalArgumentException("regionSize must be a positive multiple of cellSize");
        }
        if (oceanRatio <= 0.0 || oceanRatio >= 1.0) {
            throw new IllegalArgumentException("oceanRatio must be in (0, 1)");
        }
        if (maxSurfaceHeight <= seaLevel) {
            throw new IllegalArgumentException("maxSurfaceHeight must be above seaLevel");
        }
        if (mountainRangeProbability < 0.0 || mountainRangeProbability > 1.0) {
            throw new IllegalArgumentException("mountainRangeProbability must be in [0, 1]");
        }
        if (plainsSlope <= 0.0 || hillsSlope <= 0.0 || plateauSlope <= 0.0 || mountainSlope <= 0.0) {
            throw new IllegalArgumentException("slopes must be positive");
        }
    }

    public int cellsPerRegion() {
        return regionSize / cellSize;
    }
}
