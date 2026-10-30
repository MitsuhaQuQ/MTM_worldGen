package com.mtm.worldgen.logic;

import java.util.Arrays;

/**
 * Refined river courses around one logic region, as straight segments with a spatial index. Holds every segment
 * that can reach into the region, so a position only ever needs its own region. Immutable.
 */
public final class RiverRegion {
    /** Nearest river at a position: distance to its center line in blocks, and its width and depth there. */
    public record Hit(double distance, double width, double depth) {
    }

    /** Values per segment: x0, z0, x1, z1, width0, width1, depth0, depth1. */
    private static final int STRIDE = 8;
    private static final FlowRegion.RiverClass[] CLASSES = FlowRegion.RiverClass.values();

    private final int originX;
    private final int originZ;
    private final int bucketSize;
    private final int buckets;
    private final double reach;
    private final double[] segments;
    private final byte[] classes;
    private final int[][] index;

    /**
     * @param originX    block X of the region's corner
     * @param originZ    block Z of the region's corner
     * @param bucketSize edge length of the index buckets in blocks
     * @param buckets    index buckets per axis
     * @param reach      farthest distance a river is reported at
     * @param segments   segment values, {@link #STRIDE} per segment
     * @param classes    river class of each segment
     * @param index      segment numbers per bucket
     */
    RiverRegion(int originX, int originZ, int bucketSize, int buckets, double reach, double[] segments, byte[] classes, int[][] index) {
        this.reach = reach;
        this.originX = originX;
        this.originZ = originZ;
        this.bucketSize = bucketSize;
        this.buckets = buckets;
        this.segments = segments;
        this.classes = classes;
        this.index = index;
    }

    /** Nearest river within the index reach of a position inside the region, or null if there is none. */
    public Hit nearest(double x, double z) {
        int bucketX = Math.max(0, Math.min(buckets - 1, (int) Math.floor((x - originX) / bucketSize)));
        int bucketZ = Math.max(0, Math.min(buckets - 1, (int) Math.floor((z - originZ) / bucketSize)));
        double best = Double.MAX_VALUE;
        double bestWidth = 0.0;
        double bestDepth = 0.0;
        for (int segment : index[bucketZ * buckets + bucketX]) {
            int s = segment * STRIDE;
            double dx = segments[s + 2] - segments[s];
            double dz = segments[s + 3] - segments[s + 1];
            double t = Math.max(0.0, Math.min(1.0, ((x - segments[s]) * dx + (z - segments[s + 1]) * dz) / (dx * dx + dz * dz)));
            double distance = Math.hypot(x - segments[s] - t * dx, z - segments[s + 1] - t * dz);
            if (distance < best) {
                best = distance;
                bestWidth = segments[s + 4] + t * (segments[s + 5] - segments[s + 4]);
                bestDepth = segments[s + 6] + t * (segments[s + 7] - segments[s + 6]);
            }
        }
        // Buckets also hold segments a little beyond the reach, which would make the answer depend on the bucket
        return best > reach ? null : new Hit(best, bestWidth, bestDepth);
    }

    public int segments() {
        return classes.length;
    }

    public double startX(int segment) {
        return segments[segment * STRIDE];
    }

    public double startZ(int segment) {
        return segments[segment * STRIDE + 1];
    }

    public double endX(int segment) {
        return segments[segment * STRIDE + 2];
    }

    public double endZ(int segment) {
        return segments[segment * STRIDE + 3];
    }

    public double width(int segment) {
        return segments[segment * STRIDE + 4];
    }

    public FlowRegion.RiverClass riverClass(int segment) {
        return CLASSES[classes[segment]];
    }

    /** Whether this region holds exactly the same segments as another. */
    public boolean contentEquals(RiverRegion other) {
        return originX == other.originX && originZ == other.originZ && Arrays.equals(segments, other.segments)
                && Arrays.equals(classes, other.classes) && Arrays.deepEquals(index, other.index);
    }
}
