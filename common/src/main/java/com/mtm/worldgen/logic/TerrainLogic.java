package com.mtm.worldgen.logic;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entry point to the terrain logic of one world. Large-scale values live on the coarse grid and are
 * cached per logic region; block-level surface height is a Catmull-Rom interpolation of cell centers.
 * Everything is a function of seed + coordinates + settings, independent of evaluation order.
 */
public final class TerrainLogic {
    private static final int CACHE_CAPACITY = 64;
    /** Spawn search radius in coarse cells (64 * 64 blocks = 4096 blocks by default). */
    private static final int SPAWN_SEARCH_RINGS = 64;
    private static final double SPAWN_MIN_ABOVE_SEA = 3.0;
    private static final double SPAWN_MAX_ABOVE_SEA = 40.0;
    private static final Map<Long, TerrainLogic> BY_SEED = new ConcurrentHashMap<>();

    private final WorldgenSettings settings;
    private final ContinentLayer continents;
    private final RegionCache regions;
    private final ThreadLocal<LogicRegion> lastRegion = new ThreadLocal<>();
    private final ThreadLocal<ColumnCache> columns = ThreadLocal.withInitial(ColumnCache::new);

    public TerrainLogic(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.continents = new ContinentLayer(seed, settings);
        this.regions = new RegionCache(this::generateRegion, CACHE_CAPACITY);
    }

    /** Shared instance for a world seed, using the default settings until per-world config exists. */
    public static TerrainLogic forSeed(long seed) {
        return BY_SEED.computeIfAbsent(seed, s -> new TerrainLogic(s, WorldgenSettings.DEFAULT));
    }

    public WorldgenSettings settings() {
        return settings;
    }

    public LogicRegion region(int regionX, int regionZ) {
        return regions.get(regionX, regionZ);
    }

    /** Coarse surface height at a cell center, by global cell index. */
    public double cellHeight(int cellX, int cellZ) {
        int cells = settings.cellsPerRegion();
        int regionX = Math.floorDiv(cellX, cells);
        int regionZ = Math.floorDiv(cellZ, cells);
        LogicRegion region = lastRegion.get();
        if (region == null || region.regionX() != regionX || region.regionZ() != regionZ) {
            region = regions.get(regionX, regionZ);
            lastRegion.set(region);
        }
        return region.cellHeight(cellX - regionX * cells, cellZ - regionZ * cells);
    }

    /** Surface height at a block column, memoized per thread for repeated queries of the same column. */
    public double surfaceHeight(int x, int z) {
        return columns.get().get(this, x, z);
    }

    /** Surface height at any position, interpolated from the 4x4 surrounding cell centers. */
    public double sampleSurfaceHeight(double x, double z) {
        double size = settings.cellSize();
        double u = x / size - 0.5;
        double v = z / size - 0.5;
        int cellX = (int) Math.floor(u);
        int cellZ = (int) Math.floor(v);
        double tx = u - cellX;
        double tz = v - cellZ;

        double height = catmullRom(row(cellX, cellZ - 1, tx), row(cellX, cellZ, tx),
                row(cellX, cellZ + 1, tx), row(cellX, cellZ + 2, tx), tz);
        return Math.min(height, settings.maxSurfaceHeight());
    }

    private double row(int cellX, int cellZ, double t) {
        return catmullRom(cellHeight(cellX - 1, cellZ), cellHeight(cellX, cellZ),
                cellHeight(cellX + 1, cellZ), cellHeight(cellX + 2, cellZ), t);
    }

    /**
     * Initial world spawn: the coarse cell closest to the origin (searching outward ring by ring) that is
     * comfortably on land, so players do not start in the middle of an ocean.
     *
     * @return block column at the center of the chosen cell, or the origin if no land is found in range
     */
    public BlockColumn findSpawn() {
        double sea = settings.seaLevel() - 1;
        for (int ring = 0; ring <= SPAWN_SEARCH_RINGS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) == ring && isSpawnCell(dx, dz, sea)) {
                        int half = settings.cellSize() / 2;
                        return new BlockColumn(dx * settings.cellSize() + half, dz * settings.cellSize() + half);
                    }
                }
            }
        }
        return new BlockColumn(0, 0);
    }

    private boolean isSpawnCell(int cellX, int cellZ, double sea) {
        double height = cellHeight(cellX, cellZ);
        if (height < sea + SPAWN_MIN_ABOVE_SEA || height > sea + SPAWN_MAX_ABOVE_SEA) {
            return false;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (cellHeight(cellX + dx, cellZ + dz) < sea + 1.0) {
                    return false;
                }
            }
        }
        return true;
    }

    private LogicRegion generateRegion(int regionX, int regionZ) {
        int cells = settings.cellsPerRegion();
        int size = settings.cellSize();
        float[] heights = new float[cells * cells];
        for (int lz = 0; lz < cells; lz++) {
            for (int lx = 0; lx < cells; lx++) {
                double x = ((long) regionX * cells + lx + 0.5) * size;
                double z = ((long) regionZ * cells + lz + 0.5) * size;
                heights[lz * cells + lx] = (float) continents.height(x, z);
            }
        }
        return new LogicRegion(regionX, regionZ, cells, heights);
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        return p1 + 0.5 * t * (p2 - p0 + t * (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3 + t * (3.0 * (p1 - p2) + p3 - p0)));
    }

    /** Direct-mapped per-thread cache of recently sampled columns (one chunk's worth of slots). */
    private static final class ColumnCache {
        private static final int SLOTS = 256;
        private final TerrainLogic[] owners = new TerrainLogic[SLOTS];
        private final int[] xs = new int[SLOTS];
        private final int[] zs = new int[SLOTS];
        private final double[] heights = new double[SLOTS];

        double get(TerrainLogic logic, int x, int z) {
            int slot = (x & 15) | ((z & 15) << 4);
            if (owners[slot] != logic || xs[slot] != x || zs[slot] != z) {
                owners[slot] = logic;
                xs[slot] = x;
                zs[slot] = z;
                heights[slot] = logic.sampleSurfaceHeight(x, z);
            }
            return heights[slot];
        }
    }
}
