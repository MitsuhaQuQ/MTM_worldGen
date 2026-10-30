package com.mtm.worldgen.logic;

import java.util.Arrays;

/**
 * Refined river courses around one logic region, as straight segments with a spatial index. Holds every segment
 * that can reach into the region, so a position only ever needs its own region. Immutable.
 */
public final class RiverRegion {
    /**
     * Rivers around a position. Distance, width and depth are those of the river whose channel the position is
     * deepest in. The water level is a blend of all rivers in reach, weighted by closeness, so it does not jump
     * between two rivers. Land must stay between low and high, the valley walls of all rivers in reach, and keeps
     * this share of its ridge texture. Run is the distance from the nearest valley floor,
     * and floodplain how strongly the plain of a river in reach claims the land, from 0 to 1.
     */
    public record Hit(double distance, double width, double depth, double level, double low, double high, double texture, double run, double floodplain) {
        public double halfWidth(double minimumWidth) {
            return 0.5 * Math.max(width, minimumWidth);
        }
    }

    /** Values per segment: x0, z0, x1, z1, width0, width1, depth0, depth1, level0, level1. */
    private static final int STRIDE = 10;
    /** Below this distance in blocks the closest river no longer gains on the others in the water level blend. */
    private static final double BLEND_RADIUS = 4.0;
    private static final FlowRegion.RiverClass[] CLASSES = FlowRegion.RiverClass.values();

    private final int originX;
    private final int originZ;
    private final int bucketSize;
    private final int buckets;
    private final double reach;
    private final WorldgenSettings.Rivers config;
    private final double[] segments;
    private final byte[] classes;
    private final int[][] index;

    /**
     * @param originX    block X of the region's corner
     * @param originZ    block Z of the region's corner
     * @param bucketSize edge length of the index buckets in blocks
     * @param buckets    index buckets per axis
     * @param config     river settings
     * @param segments   segment values, {@link #STRIDE} per segment
     * @param classes    river class of each segment
     * @param index      segment numbers per bucket
     */
    RiverRegion(int originX, int originZ, int bucketSize, int buckets, WorldgenSettings.Rivers config, double[] segments, byte[] classes, int[][] index) {
        this.reach = config.queryReach;
        this.config = config;
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
        double bestDistance = 0.0;
        double bestWidth = 0.0;
        double bestDepth = 0.0;
        double nearestRun = Double.MAX_VALUE;
        double levelSum = 0.0;
        double weightSum = 0.0;
        double low = -Double.MAX_VALUE;
        double high = Double.MAX_VALUE;
        double texture = 1.0;
        double floodplain = 0.0;
        for (int segment : index[bucketZ * buckets + bucketX]) {
            int s = segment * STRIDE;
            double dx = segments[s + 2] - segments[s];
            double dz = segments[s + 3] - segments[s + 1];
            double t = Math.max(0.0, Math.min(1.0, ((x - segments[s]) * dx + (z - segments[s + 1]) * dz) / (dx * dx + dz * dz)));
            double offsetX = x - segments[s] - t * dx;
            double offsetZ = z - segments[s + 1] - t * dz;
            double squared = offsetX * offsetX + offsetZ * offsetZ;
            if (squared > reach * reach) {
                continue;
            }
            double distance = Math.sqrt(squared);
            double width = segments[s + 4] + t * (segments[s + 5] - segments[s + 4]);
            double level = segments[s + 8] + t * (segments[s + 9] - segments[s + 8]);
            double half = 0.5 * Math.max(width, config.minChannelWidth);
            double run = Math.max(0.0, distance - config.valleyFloorFactor * half);
            double wall = wall(run);
            double plainWidth = Math.min(config.floodplainBase + config.floodplainPerWidth * width, reach - config.valleyFloorFactor * half);
            floodplain = Math.max(floodplain, 1.0 - smooth(run / plainWidth));
            nearestRun = Math.min(nearestRun, run);
            // The fade has to end inside the reach, or the texture would jump where a river drops out of the query
            double fade = Math.min(config.textureFadeBase + config.textureFadePerWidth * width, reach - config.valleyFloorFactor * half);
            texture = Math.min(texture, smooth(run / fade));
            low = Math.max(low, level + config.bankHeight - wall);
            high = Math.min(high, level + config.bankHeight + wall);
            double closeness = 1.0 / (distance + BLEND_RADIUS);
            closeness *= closeness;
            closeness *= closeness;
            levelSum += closeness * level;
            weightSum += closeness;
            double ratio = distance / half;
            if (ratio < best) {
                best = ratio;
                bestDistance = distance;
                bestWidth = width;
                bestDepth = segments[s + 6] + t * (segments[s + 7] - segments[s + 6]);
            }
        }
        return best == Double.MAX_VALUE ? null : new Hit(bestDistance, bestWidth, bestDepth, levelSum / weightSum, low, high, texture, nearestRun, floodplain);
    }

    /** Height a valley wall gains over a run: gentle at the floor, steepening up to the maximum wall grade. */
    private double wall(double run) {
        double knee = (config.valleyWallMaxGrade - config.valleyWallGrade) / (2.0 * config.valleyWallCurve);
        double curved = Math.min(run, knee);
        return curved * (config.valleyWallGrade + curved * config.valleyWallCurve) + Math.max(0.0, run - knee) * config.valleyWallMaxGrade;
    }

    private static double smooth(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    public int regionX() {
        return Math.floorDiv(originX, bucketSize * buckets);
    }

    public int regionZ() {
        return Math.floorDiv(originZ, bucketSize * buckets);
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
