package com.mtm.worldgen.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Lake shapes from the real ground. The routing grid only knows the ground at one node per cell, so it decides where
 * lakes lie and caps how high they can stand, while their shores come from a finer flood of the ground around them.
 * Taken from the routing cells alone, a lake stood above the ground between the nodes nearly everywhere, by ten or
 * twenty blocks, and its water ended at a rim raised along the rounded outline of its cells: a square lake on flat
 * land, held in by a straight wall.
 * <p>
 * Each lake of the routing grid, its connected lake cells, gets a grid of the smooth land every {@code step} blocks
 * over those cells and the cells around them. A priority flood from the rim of that window gives the level its
 * deepest point spills at, through the lowest gap on any side. The lake stands at that level, or at the routing
 * grid's where that is lower, and covers the ground below it connected to the deepest point, so its shores follow the
 * terrain. Inland sinks dig their own basin and are left to the routing grid.
 * <p>
 * The smooth land is the ground lakes fill: erosion leaves them as they are, and holds the land draining into them to
 * no lower than their water. A lake only depends on its own cells and the land around them, so the regions it reaches
 * into all find the same shape.
 */
final class LakeLayer {
    interface FlowSource {
        FlowRegion flow(int regionX, int regionZ);
    }

    interface HeightSampler {
        double height(double x, double z);
    }

    /** Routing cells around a lake's own that its window takes in. */
    private static final int MARGIN = 1;
    /** Lakes of more routing cells than this keep the part found so far, which only matters on absurd settings. */
    private static final int MAX_CELLS = 4096;
    /** A lake shallower than this at its deepest point is left out. */
    private static final double MIN_DEPTH = 1.0;

    private final WorldgenSettings settings;
    private final FlowSource flows;
    private final HeightSampler heights;
    private final int cellSize;
    private final int cells;
    private final int step;

    LakeLayer(WorldgenSettings settings, FlowSource flows, HeightSampler heights) {
        this.settings = settings;
        this.flows = flows;
        this.heights = heights;
        this.cellSize = settings.rivers.flowCellSize;
        this.cells = settings.general.regionSize / cellSize;
        this.step = settings.rivers.lakeShoreStep;
    }

    /** One lake: its water level, and the fine cells its water covers in a window of the ground. */
    static final class Shape {
        final double level;
        /** Block coordinates of the window's corner; fine cell centers lie half a step in. */
        private final int originX;
        private final int originZ;
        private final int width;
        private final int height;
        private final int step;
        private final boolean[] inside;

        Shape(double level, int originX, int originZ, int width, int height, int step, boolean[] inside) {
            this.level = level;
            this.originX = originX;
            this.originZ = originZ;
            this.width = width;
            this.height = height;
            this.step = step;
            this.inside = inside;
        }

        /** Share of the lake among the four fine cells nearest a position, blended smoothly, from 0 to 1. */
        double share(double x, double z) {
            double u = (x - originX) / step - 0.5;
            double v = (z - originZ) / step - 0.5;
            int i = (int) Math.floor(u);
            int j = (int) Math.floor(v);
            if (i < -1 || j < -1 || i >= width || j >= height) {
                return 0.0;
            }
            double tx = smoothstep(u - i);
            double tz = smoothstep(v - j);
            return (1.0 - tz) * ((1.0 - tx) * at(i, j) + tx * at(i + 1, j)) + tz * ((1.0 - tx) * at(i, j + 1) + tx * at(i + 1, j + 1));
        }

        private double at(int i, int j) {
            return i >= 0 && j >= 0 && i < width && j < height && inside[j * width + i] ? 1.0 : 0.0;
        }

        /** Whether two shapes are the same lake, for tests. */
        boolean contentEquals(Shape other) {
            return level == other.level && originX == other.originX && originZ == other.originZ && width == other.width
                    && height == other.height && Arrays.equals(inside, other.inside);
        }
    }

    /** The lakes that reach into each routing cell of a logic region. */
    static final class LakeRegion {
        private static final Shape[] NONE = new Shape[0];

        private final int regionX;
        private final int regionZ;
        private final int cells;
        private final Shape[][] byCell;

        LakeRegion(int regionX, int regionZ, int cells, Shape[][] byCell) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.cells = cells;
            this.byCell = byCell;
        }

        int regionX() {
            return regionX;
        }

        int regionZ() {
            return regionZ;
        }

        /** Lakes whose window covers a routing cell of the region, by local cell index. */
        Shape[] at(int localX, int localZ) {
            Shape[] shapes = byCell[localZ * cells + localX];
            return shapes == null ? NONE : shapes;
        }
    }

    LakeRegion compute(int regionX, int regionZ) {
        int originX = regionX * cells;
        int originZ = regionZ * cells;
        List<List<Shape>> byCell = new ArrayList<>(cells * cells);
        for (int i = 0; i < cells * cells; i++) {
            byCell.add(null);
        }
        // Any lake whose window reaches this region has a cell within the margin of it
        Set<Long> seen = new HashSet<>();
        for (int cellZ = originZ - MARGIN; cellZ < originZ + cells + MARGIN; cellZ++) {
            for (int cellX = originX - MARGIN; cellX < originX + cells + MARGIN; cellX++) {
                if (seen.contains(key(cellX, cellZ)) || !isLake(cellX, cellZ)) {
                    continue;
                }
                List<int[]> lake = component(cellX, cellZ, seen);
                Shape shape = lake == null ? null : shape(lake);
                if (shape == null) {
                    continue;
                }
                int minX = Integer.MAX_VALUE;
                int minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxZ = Integer.MIN_VALUE;
                for (int[] cell : lake) {
                    minX = Math.min(minX, cell[0]);
                    minZ = Math.min(minZ, cell[1]);
                    maxX = Math.max(maxX, cell[0]);
                    maxZ = Math.max(maxZ, cell[1]);
                }
                for (int z = Math.max(minZ - MARGIN, originZ); z <= Math.min(maxZ + MARGIN, originZ + cells - 1); z++) {
                    for (int x = Math.max(minX - MARGIN, originX); x <= Math.min(maxX + MARGIN, originX + cells - 1); x++) {
                        int index = (z - originZ) * cells + (x - originX);
                        if (byCell.get(index) == null) {
                            byCell.set(index, new ArrayList<>(1));
                        }
                        byCell.get(index).add(shape);
                    }
                }
            }
        }
        Shape[][] shapes = new Shape[cells * cells][];
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = byCell.get(i) == null ? null : byCell.get(i).toArray(new Shape[0]);
        }
        return new LakeRegion(regionX, regionZ, cells, shapes);
    }

    /**
     * The lake cells connected to one, at the same level, or null for an inland sink. All cells found are marked as
     * seen, and the cells are listed in an order that only depends on the lake, not the cell the search started from.
     */
    private List<int[]> component(int startX, int startZ, Set<Long> seen) {
        float level = lakeLevel(startX, startZ);
        List<int[]> lake = new ArrayList<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{startX, startZ});
        seen.add(key(startX, startZ));
        boolean sink = false;
        while (!queue.isEmpty() && lake.size() < MAX_CELLS) {
            int[] cell = queue.poll();
            lake.add(cell);
            sink |= isSink(cell[0], cell[1]);
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cell[0] + dx;
                    int z = cell[1] + dz;
                    if (!seen.contains(key(x, z)) && isLake(x, z) && lakeLevel(x, z) == level) {
                        seen.add(key(x, z));
                        queue.add(new int[]{x, z});
                    }
                }
            }
        }
        if (sink) {
            return null;
        }
        lake.sort(Comparator.<int[]>comparingInt(c -> c[1]).thenComparingInt(c -> c[0]));
        return lake;
    }

    /** The shape of a lake from the ground around its cells, or null where it holds no water. */
    private Shape shape(List<int[]> lake) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int[] cell : lake) {
            minX = Math.min(minX, cell[0]);
            minZ = Math.min(minZ, cell[1]);
            maxX = Math.max(maxX, cell[0]);
            maxZ = Math.max(maxZ, cell[1]);
        }
        int originX = (minX - MARGIN) * cellSize;
        int originZ = (minZ - MARGIN) * cellSize;
        int width = (maxX - minX + 1 + 2 * MARGIN) * cellSize / step;
        int height = (maxZ - minZ + 1 + 2 * MARGIN) * cellSize / step;
        int n = width * height;
        double[] ground = new double[n];
        LogicThreads.forEach(height, j -> {
            for (int i = 0; i < width; i++) {
                ground[j * width + i] = heights.height(originX + (i + 0.5) * step, originZ + (j + 0.5) * step);
            }
        });
        double level = lakeLevel(lake.get(0)[0], lake.get(0)[1]);
        // The deepest point of the ground within the lake's own cells
        boolean[] own = new boolean[n];
        int per = cellSize / step;
        for (int[] cell : lake) {
            int i0 = (cell[0] - minX + MARGIN) * per;
            int j0 = (cell[1] - minZ + MARGIN) * per;
            for (int j = j0; j < j0 + per; j++) {
                for (int i = i0; i < i0 + per; i++) {
                    own[j * width + i] = true;
                }
            }
        }
        int deepest = -1;
        for (int index = 0; index < n; index++) {
            if (own[index] && (deepest < 0 || ground[index] < ground[deepest])) {
                deepest = index;
            }
        }
        double spill = spillLevel(ground, width, height, deepest);
        level = Math.min(level, spill);
        if (level - ground[deepest] < MIN_DEPTH) {
            return null;
        }
        // The water: ground below the level, connected to the deepest point
        boolean[] inside = new boolean[n];
        int[] queue = new int[n];
        int count = 0;
        queue[count++] = deepest;
        inside[deepest] = true;
        for (int head = 0; head < count; head++) {
            int cell = queue[head];
            int i = cell % width;
            int j = cell / width;
            for (int d = 0; d < 8; d++) {
                int ni = i + FlowRegion.DIRECTION_X[d];
                int nj = j + FlowRegion.DIRECTION_Z[d];
                int next = nj * width + ni;
                if (ni >= 0 && nj >= 0 && ni < width && nj < height && !inside[next] && ground[next] < level) {
                    inside[next] = true;
                    queue[count++] = next;
                }
            }
        }
        return new Shape(level, originX, originZ, width, height, step, inside);
    }

    /**
     * The level water at a cell of the window spills at: the lowest height it has to rise to on any path to the rim of
     * the window, found by priority flood from the rim.
     */
    private static double spillLevel(double[] ground, int width, int height, int target) {
        int n = ground.length;
        double[] filled = new double[n];
        boolean[] reached = new boolean[n];
        PriorityQueue<long[]> queue = new PriorityQueue<>(Comparator.<long[]>comparingLong(e -> e[0]).thenComparingLong(e -> e[1]));
        for (int index = 0; index < n; index++) {
            int i = index % width;
            int j = index / width;
            if (i == 0 || j == 0 || i == width - 1 || j == height - 1) {
                reached[index] = true;
                filled[index] = ground[index];
                queue.add(new long[]{encode(filled[index]), index});
            }
        }
        while (!queue.isEmpty()) {
            int cell = (int) queue.poll()[1];
            if (cell == target) {
                return filled[cell];
            }
            int i = cell % width;
            int j = cell / width;
            for (int d = 0; d < 8; d++) {
                int ni = i + FlowRegion.DIRECTION_X[d];
                int nj = j + FlowRegion.DIRECTION_Z[d];
                int next = nj * width + ni;
                if (ni >= 0 && nj >= 0 && ni < width && nj < height && !reached[next]) {
                    reached[next] = true;
                    filled[next] = Math.max(ground[next], filled[cell]);
                    queue.add(new long[]{encode(filled[next]), next});
                }
            }
        }
        return filled[target];
    }

    /** Sort key of a level, ordered as the level is. */
    private static long encode(double level) {
        return Math.round(level * 4096.0);
    }

    private boolean isLake(int cellX, int cellZ) {
        FlowRegion flow = flows.flow(Math.floorDiv(cellX, cells), Math.floorDiv(cellZ, cells));
        return flow.isLake(Math.floorMod(cellX, cells), Math.floorMod(cellZ, cells));
    }

    private boolean isSink(int cellX, int cellZ) {
        FlowRegion flow = flows.flow(Math.floorDiv(cellX, cells), Math.floorDiv(cellZ, cells));
        return flow.isSink(Math.floorMod(cellX, cells), Math.floorMod(cellZ, cells));
    }

    private float lakeLevel(int cellX, int cellZ) {
        FlowRegion flow = flows.flow(Math.floorDiv(cellX, cells), Math.floorDiv(cellZ, cells));
        return flow.lakeLevel(Math.floorMod(cellX, cells), Math.floorMod(cellZ, cells));
    }

    private static long key(int cellX, int cellZ) {
        return (long) cellX << 32 | (cellZ & 0xFFFFFFFFL);
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }
}
