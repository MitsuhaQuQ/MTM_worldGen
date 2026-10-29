package com.mtm.worldgen.logic;

/**
 * Terrain logic parameters. Will be loaded from the world's locked config.json; until then
 * {@link #DEFAULT} holds the values from the design document.
 *
 * @param seaLevel         vanilla sea level field; the top water block sits at seaLevel - 1 (y=62)
 * @param maxSurfaceHeight highest surface height terrain may reach
 * @param regionSize       logic region edge length in blocks
 * @param cellSize         coarse grid cell edge length in blocks
 * @param lookAhead        radius in blocks read around a region when computing it
 * @param oceanRatio       target fraction of the surface covered by ocean
 */
public record WorldgenSettings(int seaLevel, int maxSurfaceHeight, int regionSize, int cellSize, int lookAhead,
                               double oceanRatio) {
    public static final WorldgenSettings DEFAULT = new WorldgenSettings(63, 300, 4096, 64, 4096, 0.30);

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
    }

    public int cellsPerRegion() {
        return regionSize / cellSize;
    }
}
