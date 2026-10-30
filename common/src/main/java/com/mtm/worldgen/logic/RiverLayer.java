package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Hashing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Refines the routing grid into river courses. Each routing cell gets a jittered node, and each flow edge a smooth
 * curve between its two nodes with meanders on top. Nodes depend on their cell and meanders on their edge only, so
 * whichever region builds an edge gets the same curve, and rivers join up across region borders without any matching.
 * Water levels come from the routing grid at the nodes and follow the real ground along the curve between them.
 */
final class RiverLayer {
    private static final long NODE_SALT = 0x4E4F4445L;
    private static final long MEANDER_SALT = 0x4D45414EL;
    private static final long SIZE_SALT = 0x53495A45L;

    interface FlowSource {
        FlowRegion flowRegion(int regionX, int regionZ);
    }

    private final WorldgenSettings settings;
    private final WorldgenSettings.Rivers config;
    private final FlowSource flows;
    private final FlowLayer.HeightSampler landform;
    private final long nodeSeed;
    private final long meanderSeed;
    private final long sizeSeed;
    private final int cellSize;
    private final int cells;

    RiverLayer(long seed, WorldgenSettings settings, FlowSource flows, FlowLayer.HeightSampler landform) {
        this.settings = settings;
        this.config = settings.rivers;
        this.flows = flows;
        this.landform = landform;
        this.nodeSeed = nodeSeed(seed);
        this.meanderSeed = Hashing.derive(seed, MEANDER_SALT);
        this.sizeSeed = Hashing.derive(seed, SIZE_SALT);
        this.cellSize = config.flowCellSize;
        this.cells = settings.general.regionSize / cellSize;
    }

    RiverRegion compute(int regionX, int regionZ) {
        // The nine regions around this one hold every edge it can need, so fetch them all at once
        FlowRegion[] around = new FlowRegion[9];
        LogicThreads.forEach(around.length, i -> around[i] = flows.flowRegion(regionX + i % 3 - 1, regionZ + i / 3 - 1));

        int originX = regionX * cells;
        int originZ = regionZ * cells;
        int margin = Math.max(1, ceilDiv(config.queryReach, cellSize) + 2);
        // Rows run in parallel into their own lists and join in row order, so the result does not depend on timing
        int rows = cells + 2 * margin;
        Curves[] parts = new Curves[rows];
        LogicThreads.forEach(rows, row -> {
            Curves part = new Curves();
            for (int cellX = originX - margin; cellX < originX + cells + margin; cellX++) {
                addEdge(around, regionX, regionZ, cellX, originZ - margin + row, part);
            }
            parts[row] = part;
        });
        Curves curves = new Curves();
        for (Curves part : parts) {
            curves.addAll(part);
        }
        return curves.index(regionX * settings.general.regionSize, regionZ * settings.general.regionSize);
    }

    private void addEdge(FlowRegion[] around, int regionX, int regionZ, int cellX, int cellZ, Curves curves) {
        FlowRegion flow = flowAt(around, regionX, regionZ, cellX, cellZ);
        int lx = Math.floorMod(cellX, cells);
        int lz = Math.floorMod(cellZ, cells);
        if (flow == null || !flow.flows(lx, lz) || flow.riverClass(lx, lz) == FlowRegion.RiverClass.NONE) {
            return;
        }
        int nextX = cellX + flow.downstreamX(lx, lz);
        int nextZ = cellZ + flow.downstreamZ(lx, lz);
        FlowRegion nextFlow = flowAt(around, regionX, regionZ, nextX, nextZ);
        if (nextFlow == null) {
            return;
        }
        int nextLx = Math.floorMod(nextX, cells);
        int nextLz = Math.floorMod(nextZ, cells);
        if (flow.isLake(lx, lz) && nextFlow.isLake(nextLx, nextLz)) {
            return;
        }
        double[] a = smoothNode(around, regionX, regionZ, cellX, cellZ);
        double[] b = smoothNode(around, regionX, regionZ, nextX, nextZ);
        // Tangents run from the neighbor behind to the neighbor ahead, so the curves of consecutive edges join smoothly
        double[] before = flow.hasInflow(lx, lz)
                ? smoothNode(around, regionX, regionZ, cellX + flow.inflowX(lx, lz), cellZ + flow.inflowZ(lx, lz)) : a;
        double[] after = nextFlow.flows(nextLx, nextLz)
                ? smoothNode(around, regionX, regionZ, nextX + nextFlow.downstreamX(nextLx, nextLz), nextZ + nextFlow.downstreamZ(nextLx, nextLz)) : b;

        double[] startSize = size(around, regionX, regionZ, flow.riverClass(lx, lz), flow.catchment(lx, lz), cellX, cellZ);
        FlowRegion.RiverClass nextClass = nextFlow.riverClass(nextLx, nextLz);
        // A side branch keeps its own size into the confluence, or it would flare out to the width of the main river
        boolean mainStem = nextFlow.hasInflow(nextLx, nextLz) && nextX + nextFlow.inflowX(nextLx, nextLz) == cellX
                && nextZ + nextFlow.inflowZ(nextLx, nextLz) == cellZ;
        double[] endSize = nextClass == FlowRegion.RiverClass.NONE || !mainStem ? startSize
                : size(around, regionX, regionZ, nextClass, nextFlow.catchment(nextLx, nextLz), nextX, nextZ);

        double startLevel = flow.waterLevel(lx, lz);
        double endLevel = nextFlow.waterLevel(nextLx, nextLz);

        long hash = Hashing.derive(Hashing.hash(meanderSeed, cellX, cellZ), Hashing.hash(meanderSeed, nextX, nextZ));
        double phase1 = 2.0 * Math.PI * Hashing.unit(hash);
        double phase2 = 2.0 * Math.PI * Hashing.unit(Hashing.mix(hash));
        double swing = (Hashing.unit(Hashing.derive(hash, 2)) - 0.5) * 2.0;

        double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
        double normalX = -(b[1] - a[1]) / length;
        double normalZ = (b[0] - a[0]) / length;
        double[] startTangent = unit(b[0] - before[0], b[1] - before[1]);
        double[] endTangent = unit(after[0] - a[0], after[1] - a[1]);
        double c1x = a[0] + startTangent[0] * length / 3.0;
        double c1z = a[1] + startTangent[1] * length / 3.0;
        double c2x = b[0] - endTangent[0] * length / 3.0;
        double c2z = b[1] - endTangent[1] * length / 3.0;

        double previousX = a[0];
        double previousZ = a[1];
        double previousLevel = startLevel;
        byte riverClass = (byte) flow.riverClass(lx, lz).ordinal();
        for (int i = 1; i <= config.curveSegments; i++) {
            double t = (double) i / config.curveSegments;
            double u = 1.0 - t;
            double x = u * u * u * a[0] + 3.0 * u * u * t * c1x + 3.0 * u * t * t * c2x + t * t * t * b[0];
            double z = u * u * u * a[1] + 3.0 * u * u * t * c1z + 3.0 * u * t * t * c2z + t * t * t * b[1];
            double envelope = Math.sin(Math.PI * t);
            double meander = length * config.meander * envelope * envelope
                    * (swing + Math.sin(2.0 * Math.PI * t + phase1) + 0.5 * Math.sin(4.0 * Math.PI * t + phase2));
            x += normalX * meander;
            z += normalZ * meander;
            // Water follows the ground down between the nodes, but never below where this edge has to end up
            double chord = startLevel + t * (endLevel - startLevel);
            double level = i == config.curveSegments ? endLevel
                    : Math.max(endLevel, Math.min(previousLevel, Math.min(chord, landform.height(x, z))));
            double from = (double) (i - 1) / config.curveSegments;
            curves.add(previousX, previousZ, x, z,
                    startSize[0] + from * (endSize[0] - startSize[0]), startSize[0] + t * (endSize[0] - startSize[0]),
                    startSize[1] + from * (endSize[1] - startSize[1]), startSize[1] + t * (endSize[1] - startSize[1]),
                    previousLevel, level,
                    riverClass);
            previousX = x;
            previousZ = z;
            previousLevel = level;
        }
    }

    private FlowRegion flowAt(FlowRegion[] around, int regionX, int regionZ, int cellX, int cellZ) {
        int dx = Math.floorDiv(cellX, cells) - regionX;
        int dz = Math.floorDiv(cellZ, cells) - regionZ;
        return Math.abs(dx) <= 1 && Math.abs(dz) <= 1 ? around[(dz + 1) * 3 + dx + 1] : null;
    }

    /**
     * Node moved toward the middle of the river's neighbors before and after it. Routes only have eight directions
     * and can double back, which even a smooth curve cannot round when it has to pass through the raw node.
     */
    private double[] smoothNode(FlowRegion[] around, int regionX, int regionZ, int cellX, int cellZ) {
        double[] node = node(cellX, cellZ);
        FlowRegion flow = flowAt(around, regionX, regionZ, cellX, cellZ);
        if (flow == null) {
            return node;
        }
        int lx = Math.floorMod(cellX, cells);
        int lz = Math.floorMod(cellZ, cells);
        if (!flow.flows(lx, lz) || !flow.hasInflow(lx, lz)) {
            return node;
        }
        double[] before = node(cellX + flow.inflowX(lx, lz), cellZ + flow.inflowZ(lx, lz));
        double[] after = node(cellX + flow.downstreamX(lx, lz), cellZ + flow.downstreamZ(lx, lz));
        double s = config.nodeSmoothing;
        return new double[]{node[0] + s * (0.5 * (before[0] + after[0]) - node[0]), node[1] + s * (0.5 * (before[1] + after[1]) - node[1])};
    }

    private double[] node(int cellX, int cellZ) {
        return node(nodeSeed, config, cellX, cellZ);
    }

    static long nodeSeed(long seed) {
        return Hashing.derive(seed, NODE_SALT);
    }

    /** River node of a routing cell, the same for routing and for the curves. */
    static double[] node(long nodeSeed, WorldgenSettings.Rivers config, int cellX, int cellZ) {
        long hash = Hashing.hash(nodeSeed, cellX, cellZ);
        return new double[]{(cellX + 0.5 + config.nodeJitter * (Hashing.unit(hash) - 0.5)) * config.flowCellSize,
                (cellZ + 0.5 + config.nodeJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * config.flowCellSize};
    }

    /**
     * Width and depth of a river at a node, varying per node so that both edges meeting there agree. Rivers spread
     * out on the plains and narrow on steep ground, as water does: a fast river needs less width to carry the same flow.
     */
    private double[] size(FlowRegion[] around, int regionX, int regionZ, FlowRegion.RiverClass riverClass, int catchment, int cellX, int cellZ) {
        long hash = Hashing.hash(sizeSeed, cellX, cellZ);
        double width;
        double depth;
        double roll = Hashing.unit(Hashing.mix(hash));
        switch (riverClass) {
            case STREAM -> {
                double t = Math.min(1.0, (double) (catchment - config.streamCells) / (config.tributaryCells - config.streamCells));
                width = config.streamWidthMin + t * (config.streamWidthMax - config.streamWidthMin);
                depth = config.streamDepthMin + t * (config.streamDepthMax - config.streamDepthMin);
            }
            case TRIBUTARY -> {
                width = config.tributaryWidth;
                depth = config.tributaryDepth;
            }
            case TRUNK -> {
                width = config.trunkWidth;
                depth = config.trunkDepthMin + roll * (config.trunkDepthMax - config.trunkDepthMin);
            }
            default -> {
                width = config.majorTrunkWidth;
                depth = config.majorTrunkDepthMin + roll * (config.majorTrunkDepthMax - config.majorTrunkDepthMin);
            }
        }
        double spread = widthFactor(slopeAt(around, regionX, regionZ, cellX, cellZ));
        return new double[]{spread * width * (1.0 + config.widthVariation * (2.0 * Hashing.unit(hash) - 1.0)), depth};
    }

    private double widthFactor(double slope) {
        double t = Math.max(0.0, Math.min(1.0, (slope - config.gentleRiverSlope) / (config.steepRiverSlope - config.gentleRiverSlope)));
        t = t * t * (3.0 - 2.0 * t);
        return config.plainWidthFactor + t * (config.steepWidthFactor - config.plainWidthFactor);
    }

    /** Fall of the water from a cell to the next one, per block. */
    private double slopeAt(FlowRegion[] around, int regionX, int regionZ, int cellX, int cellZ) {
        FlowRegion flow = flowAt(around, regionX, regionZ, cellX, cellZ);
        int lx = Math.floorMod(cellX, cells);
        int lz = Math.floorMod(cellZ, cells);
        if (flow == null || !flow.flows(lx, lz)) {
            return 0.0;
        }
        int dx = flow.downstreamX(lx, lz);
        int dz = flow.downstreamZ(lx, lz);
        FlowRegion next = flowAt(around, regionX, regionZ, cellX + dx, cellZ + dz);
        if (next == null) {
            return 0.0;
        }
        double fall = flow.waterLevel(lx, lz) - next.waterLevel(Math.floorMod(cellX + dx, cells), Math.floorMod(cellZ + dz, cells));
        return fall / (Math.hypot(dx, dz) * cellSize);
    }

    private static double[] unit(double x, double z) {
        double length = Math.hypot(x, z);
        return length > 0.0 ? new double[]{x / length, z / length} : new double[]{0.0, 0.0};
    }

    private static int ceilDiv(int value, int divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    /** Collects segments and sorts them into the buckets within reach of them. */
    private final class Curves {
        private static final int STRIDE = 10;
        private double[] values = new double[STRIDE * 1024];
        private byte[] classes = new byte[1024];
        private int count;

        void add(double x0, double z0, double x1, double z1, double w0, double w1, double d0, double d1, double l0, double l1, byte riverClass) {
            if (count == classes.length) {
                values = Arrays.copyOf(values, values.length * 2);
                classes = Arrays.copyOf(classes, classes.length * 2);
            }
            int s = count * STRIDE;
            values[s] = x0;
            values[s + 1] = z0;
            values[s + 2] = x1;
            values[s + 3] = z1;
            values[s + 4] = w0;
            values[s + 5] = w1;
            values[s + 6] = d0;
            values[s + 7] = d1;
            values[s + 8] = l0;
            values[s + 9] = l1;
            classes[count++] = riverClass;
        }

        void addAll(Curves other) {
            for (int i = 0; i < other.count; i++) {
                int s = i * STRIDE;
                add(other.values[s], other.values[s + 1], other.values[s + 2], other.values[s + 3], other.values[s + 4], other.values[s + 5],
                        other.values[s + 6], other.values[s + 7], other.values[s + 8], other.values[s + 9], other.classes[i]);
            }
        }

        RiverRegion index(int originX, int originZ) {
            int bucketSize = config.flowCellSize;
            int buckets = cells;
            int reach = config.queryReach;
            List<List<Integer>> lists = new ArrayList<>(buckets * buckets);
            for (int i = 0; i < buckets * buckets; i++) {
                lists.add(new ArrayList<>());
            }
            for (int segment = 0; segment < count; segment++) {
                int s = segment * STRIDE;
                int firstX = bucket(Math.min(values[s], values[s + 2]) - reach - originX, bucketSize);
                int lastX = bucket(Math.max(values[s], values[s + 2]) + reach - originX, bucketSize);
                int firstZ = bucket(Math.min(values[s + 1], values[s + 3]) - reach - originZ, bucketSize);
                int lastZ = bucket(Math.max(values[s + 1], values[s + 3]) + reach - originZ, bucketSize);
                for (int z = Math.max(0, firstZ); z <= Math.min(buckets - 1, lastZ); z++) {
                    for (int x = Math.max(0, firstX); x <= Math.min(buckets - 1, lastX); x++) {
                        lists.get(z * buckets + x).add(segment);
                    }
                }
            }
            int[][] index = new int[buckets * buckets][];
            for (int i = 0; i < index.length; i++) {
                index[i] = lists.get(i).stream().mapToInt(Integer::intValue).toArray();
            }
            return new RiverRegion(originX, originZ, bucketSize, buckets, config, Arrays.copyOf(values, count * STRIDE),
                    Arrays.copyOf(classes, count), index);
        }

        private int bucket(double offset, int bucketSize) {
            return (int) Math.floor(offset / bucketSize);
        }
    }
}
