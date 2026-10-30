package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Pipeline steps 4 and 5, drainage and lakes: water is routed from the sea inland by priority flood, which fills
 * depressions up to their spill point, so every cell drains and filled cells are lakes. Catchments add up along
 * the routes. River course detail comes later; this grid only decides which way water goes, and how much of it.
 * <p>
 * All routing happens on one sparse grid, in a window reaching flowLookAhead around a region. Two grids of different
 * resolution would route some rivers differently and disagree on how big they are, and the rim of so wide a window
 * is far enough away that neighboring regions see the same rivers. A catchment counts upstream cells within
 * flowLookAhead of the cell itself, so its value does not depend on the region.
 */
final class FlowLayer {
    private static final long ROUTE_SALT = 0x524F555445L;
    /** Height tiles kept: enough for the window, which spans 12 tiles per axis by default. */
    private static final int TILES = 256;

    interface HeightSampler {
        double height(double x, double z);
    }

    private final WorldgenSettings settings;
    private final WorldgenSettings.Rivers config;
    private final HeightSampler heights;
    private final double sea;
    /** Area of a routing cell in coarse cells, the unit of catchments. */
    private final int cellArea;
    private final Fbm routeNoise;
    private final RegionCache<float[]> tiles;

    FlowLayer(long seed, WorldgenSettings settings, HeightSampler heights) {
        this.settings = settings;
        this.config = settings.rivers;
        this.heights = heights;
        this.sea = settings.general.seaLevel - 1;
        int ratio = config.flowCellSize / settings.general.cellSize;
        this.cellArea = ratio * ratio;
        this.routeNoise = new Fbm(Hashing.derive(seed, ROUTE_SALT), 2, config.routeWavelength);
        this.tiles = new RegionCache<>(this::tile, TILES);
    }

    /** Heights of one region's worth of routing cells. Shared by all windows that overlap it. */
    private float[] tile(int tileX, int tileZ) {
        int cellSize = config.flowCellSize;
        int size = settings.general.regionSize / cellSize;
        float[] tile = new float[size * size];
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double blockX = ((long) tileX * size + x + 0.5) * cellSize;
                double blockZ = ((long) tileZ * size + z + 0.5) * cellSize;
                double height = heights.height(blockX, blockZ);
                tile[z * size + x] = (float) (height < sea ? height : Math.max(sea, height + config.routeNoise * routeNoise.sample(blockX, blockZ)));
            }
        }
        return tile;
    }

    FlowRegion compute(int regionX, int regionZ) {
        int cells = settings.general.regionSize / config.flowCellSize;
        int originX = regionX * cells;
        int originZ = regionZ * cells;
        int reach = ceilDiv(config.flowLookAhead, config.flowCellSize);
        int radius = reach + 1;
        Grid grid = new Grid(originX - radius, originZ - radius, cells + 2 * radius);
        int[] catchment = accumulate(grid, reach);
        int[] nearby = accumulate(grid, reach / 2);
        int[] mainInflow = mainInflows(grid, nearby);
        int[] tributaries = countTributaries(grid, nearby, mainInflow, reach / 2,
                ceilDiv(config.tributaryCells, cellArea), ceilDiv(config.trunkCells, cellArea));

        int size = cells * cells;
        byte[] directions = new byte[size];
        byte[] inflows = new byte[size];
        int[] regionCatchment = new int[size];
        int[] regionTributaries = new int[size];
        byte[] classes = new byte[size];
        float[] lakeLevels = new float[size];
        for (int lz = 0; lz < cells; lz++) {
            for (int lx = 0; lx < cells; lx++) {
                int source = grid.index(originX + lx, originZ + lz);
                int target = lz * cells + lx;
                directions[target] = grid.direction[source];
                inflows[target] = mainInflow[source] < 0 ? -1 : (byte) (7 - grid.direction[mainInflow[source]]);
                regionCatchment[target] = catchment[source] * cellArea;
                regionTributaries[target] = tributaries[source];
                classes[target] = (byte) classify(regionCatchment[target], tributaries[source]).ordinal();
                lakeLevels[target] = grid.filled[source] - grid.height[source] >= config.lakeMinDepth ? grid.filled[source] : Float.NaN;
            }
        }
        return new FlowRegion(regionX, regionZ, cells, directions, inflows, regionCatchment, regionTributaries, classes, lakeLevels);
    }

    private FlowRegion.RiverClass classify(int catchment, int tributaries) {
        if (catchment >= config.trunkCells) {
            return tributaries > config.majorTrunkTributaries ? FlowRegion.RiverClass.MAJOR_TRUNK : FlowRegion.RiverClass.TRUNK;
        }
        if (catchment >= config.tributaryCells) {
            return FlowRegion.RiverClass.TRIBUTARY;
        }
        return catchment >= config.streamCells ? FlowRegion.RiverClass.STREAM : FlowRegion.RiverClass.NONE;
    }

    /** Land cells of the grid drain into every cell downstream of them, as far as reach cells from where they start. */
    private int[] accumulate(Grid grid, int reach) {
        int[] catchment = new int[grid.height.length];
        for (int cell = 0; cell < catchment.length; cell++) {
            if (grid.height[cell] >= sea) {
                walk(grid, cell, cell, reach, catchment, 1);
            }
        }
        return catchment;
    }

    /** The biggest inflow of each cell, or -1. Ties go to the higher index, which only depends on coordinates. */
    private static int[] mainInflows(Grid grid, int[] catchment) {
        int size = catchment.length;
        int[] mainInflow = new int[size];
        Arrays.fill(mainInflow, -1);
        for (int cell = 0; cell < size; cell++) {
            int down = grid.down[cell];
            if (down >= 0 && (mainInflow[down] < 0 || catchment[cell] > catchment[mainInflow[down]]
                    || catchment[cell] == catchment[mainInflow[down]] && cell > mainInflow[down])) {
                mainInflow[down] = cell;
            }
        }
        return mainInflow;
    }

    /**
     * A tributary is a side branch reaching a trunk: at least tributaryCells big, joining at a cell of at least
     * trunkCells, and not the biggest inflow there, which is the trunk itself. Summing branches along the way makes
     * two meeting trunks add up, plus one for the trunk that gets absorbed.
     */
    private int[] countTributaries(Grid grid, int[] catchment, int[] mainInflow, int reach, int tributaryCells, int trunkCells) {
        int size = catchment.length;
        int[] tributaries = new int[size];
        for (int cell = 0; cell < size; cell++) {
            int down = grid.down[cell];
            if (down >= 0 && cell != mainInflow[down] && catchment[cell] >= tributaryCells && catchment[down] >= trunkCells) {
                walk(grid, cell, down, reach, tributaries, 1);
            }
        }
        return tributaries;
    }

    private static void walk(Grid grid, int source, int start, int reach, int[] values, int amount) {
        int sourceX = grid.cellX(source);
        int sourceZ = grid.cellZ(source);
        for (int current = start; current >= 0; current = grid.down[current]) {
            if (Math.max(Math.abs(grid.cellX(current) - sourceX), Math.abs(grid.cellZ(current) - sourceZ)) > reach) {
                return;
            }
            values[current] += amount;
        }
    }

    private static int ceilDiv(int value, int divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    /** Square window of a cell grid with heights and flow directions. */
    private final class Grid {
        final int originX;
        final int originZ;
        final int size;
        final float[] height;
        /** Water surface once depressions have filled up to their spill point. */
        final float[] filled;
        final byte[] direction;
        final int[] down;

        Grid(int originX, int originZ, int size) {
            this.originX = originX;
            this.originZ = originZ;
            this.size = size;
            this.height = new float[size * size];
            this.filled = new float[size * size];
            this.direction = new byte[size * size];
            this.down = new int[size * size];
            int tileSize = settings.general.regionSize / config.flowCellSize;
            int firstX = Math.floorDiv(originX, tileSize);
            int firstZ = Math.floorDiv(originZ, tileSize);
            int tilesAcross = Math.floorDiv(originX + size - 1, tileSize) - firstX + 1;
            LogicThreads.forEach(tilesAcross * tilesAcross, i -> tiles.get(firstX + i % tilesAcross, firstZ + i / tilesAcross));
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    int cellX = originX + x;
                    int cellZ = originZ + z;
                    float[] tile = tiles.get(Math.floorDiv(cellX, tileSize), Math.floorDiv(cellZ, tileSize));
                    height[z * size + x] = tile[Math.floorMod(cellZ, tileSize) * tileSize + Math.floorMod(cellX, tileSize)];
                }
            }
            route();
        }

        /**
         * Priority flood: starts at the sea and the window's rim, then always continues from the lowest water level
         * reached so far. Each cell drains to the cell that reached it, so depressions and flats route out through
         * their spill point. Ties go by arrival, which keeps the result independent of anything but the heights.
         */
        private void route() {
            PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.<long[]>comparingLong(e -> e[0]).thenComparingLong(e -> e[1]));
            boolean[] reached = new boolean[height.length];
            long arrival = 0;
            for (int cell = 0; cell < height.length; cell++) {
                direction[cell] = -1;
                down[cell] = -1;
                int x = cell % size;
                int z = cell / size;
                if (height[cell] < sea || x == 0 || z == 0 || x == size - 1 || z == size - 1) {
                    reached[cell] = true;
                    filled[cell] = height[cell];
                    queue.add(new long[]{encode(height[cell]), arrival++, cell});
                }
            }
            while (!queue.isEmpty()) {
                int cell = (int) queue.poll()[2];
                int x = cell % size;
                int z = cell / size;
                for (int d = 0; d < 8; d++) {
                    int nx = x + FlowRegion.DIRECTION_X[d];
                    int nz = z + FlowRegion.DIRECTION_Z[d];
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        continue;
                    }
                    int neighbor = nz * size + nx;
                    if (!reached[neighbor]) {
                        reached[neighbor] = true;
                        filled[neighbor] = Math.max(height[neighbor], filled[cell]);
                        direction[neighbor] = (byte) (7 - d);
                        down[neighbor] = cell;
                        queue.add(new long[]{encode(filled[neighbor]), arrival++, neighbor});
                    }
                }
            }
        }

        /** Sort key of a water level. */
        private long encode(float level) {
            return Math.round(level * 4096.0);
        }

        int cellX(int index) {
            return originX + index % size;
        }

        int cellZ(int index) {
            return originZ + index / size;
        }

        int index(int cellX, int cellZ) {
            return (cellZ - originZ) * size + cellX - originX;
        }

    }
}
