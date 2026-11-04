package com.mtm.worldgen.logic;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.DoubleBinaryOperator;

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
     * and floodplain how strongly the plain of a river in reach claims the land, from 0 to 1. Sand run is the distance
     * from the water of a river mouth or delta that leaves sand along its banks, infinite if there is none in reach.
     * Strength is that of the river the position is deepest in: below 1 close to its source, where it is still growing.
     * Bar is how strongly a sediment bar along a river claims the position, from 0 to 1, and coarse how much of that bar
     * is gravel rather than sand.
     */
    public record Hit(double distance, double width, double depth, double level, double low, double high, double texture, double run, double floodplain, double sandRun,
                      double strength, double bar, double coarse) {
        public double halfWidth(double minimumWidth) {
            return 0.5 * Math.max(width, minimumWidth);
        }
    }

    /**
     * Values per segment: x0, z0, x1, z1, width0, width1, depth0, depth1, level0, level1, strength0, strength1, silt0,
     * silt1, coarse, bend. Strength grows from 0 to 1 over the first edge of a river, which starts as nothing instead of
     * a full channel. Silt is how much the river is dropping its load, from 0 to 1, coarse how much of that is gravel,
     * and bend which way and how sharply the course turns, from -1 to 1, positive to the left.
     */
    static final int STRIDE = 16;
    /** Values per alluvial fan: apex x, apex z, direction x, direction z, radius, height. */
    static final int FAN_STRIDE = 6;
    /** Values per oxbow lake: center x, center z, direction x, direction z, radius, width, water level. */
    static final int OXBOW_STRIDE = 7;
    /** Flag on a segment's class: the river leaves sand along its banks here. */
    static final byte SANDY = 0x40;
    /** Flag on a segment's class: a pool above a waterfall, which stays level end to end when rivers are fitted. */
    static final byte POOL = 0x20;
    /** Below this distance in blocks the closest river no longer gains on the others in the water level blend. */
    private static final double BLEND_RADIUS = 4.0;
    /** Share of the reach over which a river's say in blended values fades out, so nothing jumps where it leaves the query. */
    private static final double REACH_FADE = 0.25;
    /**
     * Grade at which the upper valley wall gives way over the last REACH_FADE of the reach, the steepest mountains get.
     * A wide river's floor can take up most of the reach, and land far above it, such as a plateau, was capped by the
     * wall up to the edge of the reach and then stood at its full height one block further out, as a wall a hundred
     * blocks high.
     */
    private static final double REACH_WALL_GRADE = 1.5;
    /** How steeply the land a pool holds up drops away past the ends of its segment, as a grade. */
    private static final double POOL_END_GRADE = 4.0;
    /** Strength a river counts with at its very source, where dividing by zero would otherwise loom. */
    private static final double MIN_STRENGTH = 1e-6;
    private static final long NAN_BITS = Double.doubleToRawLongBits(Double.NaN);
    /** Share of a fan's radius around its apex that counts as downstream whatever the direction. */
    private static final double APEX_ROUND = 0.1;
    /** Share of a fan's radius its rim wanders in by, and how the apex position sets where its lobes lie. */
    private static final double LOBES = 0.3;
    private static final double PHASE_X = 0.0137;
    private static final double PHASE_Z = 0.0291;
    /** Share of an oxbow's arc at each end over which it narrows to a point. */
    private static final double OXBOW_TAPER = 0.35;
    /** How much shorter one arm of an oxbow can be than the other, and how far its course swings in and out. */
    private static final double OXBOW_SKEW = 0.4;
    private static final double OXBOW_WOBBLE = 0.12;

    private static final FlowRegion.RiverClass[] CLASSES = FlowRegion.RiverClass.values();

    private final int originX;
    private final int originZ;
    private final int bucketSize;
    private final int buckets;
    private final double reach;
    private final WorldgenSettings.Rivers config;
    private final double fadeBase;
    private final double fadePerWidth;
    private final double[] segments;
    private final byte[] classes;
    /** The segment above each one along its river, or -1 where nothing above it was built. */
    private final int[] upstream;
    private final int[][] index;
    /** Alluvial fans: apex x and z, downstream direction x and z, radius and height at the apex. */
    private final double[] fans;
    /** Oxbow lakes: center x and z, direction away from the river x and z, radius, width, water level. */
    private final double[] oxbows;
    private final double oxbowSpan;
    /** Ground water levels are lowered onto, or null when they stay as routed. */
    private final DoubleBinaryOperator ground;
    /** Lowered levels per segment end, as raw long bits, NaN until first needed. */
    private final AtomicLongArray lowered;
    /** Levels per segment end lowered onto the ground right there, before looking upstream, as raw long bits. */
    private final AtomicLongArray grounded;
    /** Lake surface at a position, or NaN outside lakes; null when levels stay as routed. */
    private final DoubleBinaryOperator lake;
    /** The segment below each one, or -1 where the river ends or leaves this region's segments; null when not lowered. */
    private final int[] downstream;
    /** Surface of the lake each segment runs into a bounded way downstream, as raw long bits, NaN until first needed. */
    private final AtomicLongArray floors;

    /**
     * @param originX    block X of the region's corner
     * @param originZ    block Z of the region's corner
     * @param bucketSize edge length of the index buckets in blocks
     * @param buckets    index buckets per axis
     * @param config     river settings
     * @param fadeBase   distance from the valley floor over which texture fades, plus fadePerWidth per block of width
     * @param segments   segment values, {@link #STRIDE} per segment
     * @param classes    river class of each segment
     * @param upstream   the segment above each one, or -1
     * @param index      segment numbers per bucket
     * @param fans       alluvial fans that reach into the region, {@link #FAN_STRIDE} values each
     * @param oxbows     oxbow lakes that reach into the region, {@link #OXBOW_STRIDE} values each
     * @param oxbowSpan  degrees an oxbow's arc reaches to each side of its middle
     */
    RiverRegion(int originX, int originZ, int bucketSize, int buckets, WorldgenSettings.Rivers config, double fadeBase, double fadePerWidth,
                double[] segments, byte[] classes, int[] upstream, int[][] index, double[] fans, double[] oxbows, double oxbowSpan) {
        this(originX, originZ, bucketSize, buckets, config, fadeBase, fadePerWidth, segments, classes, upstream, index, fans, oxbows, oxbowSpan, null, null);
    }

    private RiverRegion(int originX, int originZ, int bucketSize, int buckets, WorldgenSettings.Rivers config, double fadeBase, double fadePerWidth,
                        double[] segments, byte[] classes, int[] upstream, int[][] index, double[] fans, double[] oxbows, double oxbowSpan,
                        DoubleBinaryOperator ground, DoubleBinaryOperator lake) {
        this.fans = fans;
        this.oxbows = oxbows;
        this.oxbowSpan = oxbowSpan;
        this.ground = ground;
        this.lake = lake;
        this.lowered = ground == null ? null : new AtomicLongArray(2 * classes.length);
        this.grounded = ground == null ? null : new AtomicLongArray(2 * classes.length);
        this.floors = ground == null ? null : new AtomicLongArray(classes.length);
        this.downstream = ground == null ? null : downstream(segments, upstream);
        if (lowered != null) {
            for (int i = 0; i < lowered.length(); i++) {
                lowered.set(i, NAN_BITS);
                grounded.set(i, NAN_BITS);
            }
            for (int i = 0; i < floors.length(); i++) {
                floors.set(i, NAN_BITS);
            }
        }
        this.reach = config.queryReach;
        this.config = config;
        this.fadeBase = fadeBase;
        this.fadePerWidth = fadePerWidth;
        this.originX = originX;
        this.originZ = originZ;
        this.bucketSize = bucketSize;
        this.buckets = buckets;
        this.segments = segments;
        this.classes = classes;
        this.upstream = upstream;
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
        double bestStrength = 1.0;
        double bestLevel = 0.0;
        double bestHalf = 0.0;
        double nearestRun = Double.MAX_VALUE;
        double levelSum = 0.0;
        double weightSum = 0.0;
        double low = -Double.MAX_VALUE;
        double high = Double.MAX_VALUE;
        double texture = 1.0;
        double floodplain = 0.0;
        double sandRun = Double.POSITIVE_INFINITY;
        double bar = 0.0;
        double coarse = 0.0;
        for (int segment : index[bucketZ * buckets + bucketX]) {
            int s = segment * STRIDE;
            double dx = segments[s + 2] - segments[s];
            double dz = segments[s + 3] - segments[s + 1];
            double along = ((x - segments[s]) * dx + (z - segments[s + 1]) * dz) / (dx * dx + dz * dz);
            double t = Math.max(0.0, Math.min(1.0, along));
            double offsetX = x - segments[s] - t * dx;
            double offsetZ = z - segments[s + 1] - t * dz;
            double squared = offsetX * offsetX + offsetZ * offsetZ;
            if (squared > reach * reach) {
                continue;
            }
            double distance = Math.sqrt(squared);
            double width = segments[s + 4] + t * (segments[s + 5] - segments[s + 4]);
            double start = level(segment, 0);
            double level = start + t * (level(segment, 1) - start);
            // Everything a river does grows with it from its source, so nothing jumps where it begins
            double strength = Math.max(MIN_STRENGTH, segments[s + 10] + t * (segments[s + 11] - segments[s + 10]));
            double half = 0.5 * Math.max(width, strength * config.minChannelWidth);
            double run = Math.max(0.0, distance - config.valleyFloorFactor * half);
            double wall = wall(run);
            double plainWidth = Math.min(config.floodplainBase + config.floodplainPerWidth * width, reach - config.valleyFloorFactor * half);
            double plain = strength * (1.0 - smooth(run / plainWidth));
            floodplain = Math.max(floodplain, plain);
            nearestRun = Math.min(nearestRun, run);
            if ((classes[segment] & SANDY) != 0) {
                sandRun = Math.min(sandRun, Math.max(0.0, distance - half));
            }
            double silt = strength * smooth(width / config.barFullWidth) * (segments[s + 12] + t * (segments[s + 13] - segments[s + 12]));
            if (silt > 0.0) {
                // Bars build on the inside of bends, where the water slows, and wear away on the outside
                double side = dx * offsetZ - dz * offsetX;
                double bend = segments[s + 15];
                double turning = Math.max(0.0, 1.0 + config.barBend * (side * bend > 0.0 ? Math.abs(bend) : -Math.abs(bend)));
                double barWidth = Math.min(silt * turning * (config.barBase + config.barPerWidth * width),
                        reach - distance + Math.max(0.0, distance - half) - REACH_FADE * reach);
                double edge = distance - half;
                if (barWidth > 0.0 && edge > -config.barShallow && edge < barWidth) {
                    double claim = silt * (1.0 - smooth(Math.max(0.0, edge) / barWidth));
                    if (claim > bar) {
                        bar = claim;
                        coarse = segments[s + 14];
                    }
                }
            }
            // The fade has to end inside the reach, or the texture would jump where a river drops out of the query
            // Near a source the texture stays, so a stream begins in the slope's own gully instead of a pocket
            double fade = strength * Math.min(fadeBase + fadePerWidth * width, reach - config.valleyFloorFactor * half);
            texture = Math.min(texture, fade > 0.0 ? smooth(run / fade) : 1.0);
            // Water only needs its own banks held up: the land below a river's banks falls away from the edge of its
            // channel at the steepest a valley wall gets. Held as far as the gentle floor of the valley wall reaches, a
            // steep stream beside a lower river raised the land around their meeting into a round mound, and the pools
            // of a steep stream held up a dome tens of blocks above the slope beside them. Past its ends a pool holds
            // nothing up: the pools before and after it hold their own stretch, and below its end the stream falls
            double past = (classes[segment] & POOL) != 0 ? Math.max(0.0, Math.max(-along, along - 1.0)) * Math.sqrt(dx * dx + dz * dz) : 0.0;
            double bankWall = Math.max(0.0, distance - half) * config.valleyWallMaxGrade + past * POOL_END_GRADE;
            low = Math.max(low, level + config.bankHeight - bankWall);
            double giving = Math.max(0.0, distance - (1.0 - REACH_FADE) * reach) * REACH_WALL_GRADE;
            high = Math.min(high, level + config.bankHeight + wall + giving);
            double inReach = smooth((reach - distance) / (REACH_FADE * reach));
            double closeness = 1.0 / (distance + BLEND_RADIUS);
            closeness *= closeness;
            closeness *= closeness * inReach * strength;
            levelSum += closeness * level;
            weightSum += closeness;
            double ratio = distance / half;
            if (ratio < best) {
                best = ratio;
                bestDistance = distance;
                bestWidth = width;
                bestDepth = segments[s + 6] + t * (segments[s + 7] - segments[s + 6]);
                bestStrength = strength;
                bestLevel = level;
                bestHalf = half;
            }
        }
        // A bar only reaches the shallows along the bank of the channel a position lies in, not across the water of a
        // wider river beside a narrow one
        if (bestDistance - bestHalf <= -config.barShallow) {
            bar = 0.0;
        }
        return best == Double.MAX_VALUE ? null : new Hit(bestDistance, bestWidth, bestDepth, weightSum > 0.0 ? levelSum / weightSum : bestLevel, low, high, texture, nearestRun, floodplain, sandRun, bestStrength, bar, coarse);
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

    /** The segment above this one along the main stem, or -1 at a source or where the stem leaves this region's segments. */
    int upstream(int segment) {
        return upstream[segment];
    }

    public FlowRegion.RiverClass riverClass(int segment) {
        return CLASSES[classes[segment] & ~(SANDY | POOL)];
    }

    /**
     * The same rivers with water no higher than the ground at each segment end, except in pools above waterfalls,
     * which rise above the ground by design, and no higher than anywhere rivers.fitReach nodes upstream. A river that
     * was lowered onto low ground, such as a lake lower than its routing cells, does not climb back up over the higher
     * ground below it, which would leave it running on a ridge, but cuts through at the level it reached.
     */
    RiverRegion lowered(DoubleBinaryOperator ground, DoubleBinaryOperator lake) {
        return new RiverRegion(originX, originZ, bucketSize, buckets, config, fadeBase, fadePerWidth, segments, classes, upstream, index, fans, oxbows, oxbowSpan,
                ground, lake);
    }

    /**
     * The segment below each one. Within an edge that is the next segment; past its end, the edge starting at the node
     * it ends on, which a tributary reaches as well as the main stem.
     */
    private static int[] downstream(double[] segments, int[] upstream) {
        int count = upstream.length;
        int[] below = new int[count];
        Arrays.fill(below, -1);
        java.util.Map<Long, Integer> starts = new java.util.HashMap<>();
        for (int segment = 0; segment < count; segment++) {
            int above = upstream[segment];
            if (above >= 0 && above == segment - 1) {
                below[above] = segment;
            } else {
                starts.putIfAbsent(pointKey(segments[segment * STRIDE], segments[segment * STRIDE + 1]), segment);
            }
        }
        for (int segment = 0; segment < count; segment++) {
            if (below[segment] < 0) {
                Integer next = starts.get(pointKey(segments[segment * STRIDE + 2], segments[segment * STRIDE + 3]));
                below[segment] = next != null && next != segment ? next : -1;
            }
        }
        return below;
    }

    private static long pointKey(double x, double z) {
        return Double.doubleToLongBits(x) * 31 + Double.doubleToLongBits(z);
    }

    /**
     * The oxbow lake at a position, as {its water level, how deep into it the position lies from 0 at its shore to 1 in
     * the middle of the crescent}, or null outside every one. The crescent narrows toward both ends.
     */
    double[] oxbow(double x, double z) {
        double span = Math.toRadians(oxbowSpan);
        for (int o = 0; o < oxbows.length; o += OXBOW_STRIDE) {
            double half = 0.5 * oxbows[o + 5];
            double radius = oxbows[o + 4];
            double dx = x - oxbows[o];
            double dz = z - oxbows[o + 1];
            double reach = radius + half;
            if (Math.abs(dx) >= reach || Math.abs(dz) >= reach) {
                continue;
            }
            double signed = Math.atan2(dx * oxbows[o + 3] - dz * oxbows[o + 2], dx * oxbows[o + 2] + dz * oxbows[o + 3]);
            // An old meander is no circle: its arms differ in length, and its course swings in and out along the way
            double phase = oxbows[o] * PHASE_X + oxbows[o + 1] * PHASE_Z;
            double arm = signed < 0.0 ? span * (1.0 - OXBOW_SKEW * (0.5 + 0.5 * Math.sin(3.0 * phase))) : span;
            double angle = Math.abs(signed);
            if (angle >= arm) {
                continue;
            }
            double taper = Math.sqrt(Math.min(1.0, (arm - angle) / (OXBOW_TAPER * arm)));
            double bent = radius * (1.0 + OXBOW_WOBBLE * Math.sin(2.0 * signed + phase) + 0.5 * OXBOW_WOBBLE * Math.sin(5.0 * signed + 2.0 * phase));
            double across = Math.abs(Math.sqrt(dx * dx + dz * dz) - bent) / (half * taper);
            if (across < 1.0) {
                return new double[]{oxbows[o + 6], 1.0 - across * across};
            }
        }
        return null;
    }

    /**
     * Roughly how far a position lies from the water of the nearest oxbow lake, in blocks, up to a limit: across its
     * arc from its banks, and along it past the ends of its arms. For what lies on the land around oxbows to give way
     * to them before their banks.
     */
    double oxbowClearance(double x, double z, double limit) {
        double span = Math.toRadians(oxbowSpan);
        double clearance = limit;
        for (int o = 0; o < oxbows.length; o += OXBOW_STRIDE) {
            double half = 0.5 * oxbows[o + 5];
            double radius = oxbows[o + 4];
            double dx = x - oxbows[o];
            double dz = z - oxbows[o + 1];
            double reach = radius * (1.0 + 1.5 * OXBOW_WOBBLE) + half + limit;
            if (Math.abs(dx) >= reach || Math.abs(dz) >= reach) {
                continue;
            }
            double signed = Math.atan2(dx * oxbows[o + 3] - dz * oxbows[o + 2], dx * oxbows[o + 2] + dz * oxbows[o + 3]);
            double phase = oxbows[o] * PHASE_X + oxbows[o + 1] * PHASE_Z;
            double arm = signed < 0.0 ? span * (1.0 - OXBOW_SKEW * (0.5 + 0.5 * Math.sin(3.0 * phase))) : span;
            double bent = radius * (1.0 + OXBOW_WOBBLE * Math.sin(2.0 * signed + phase) + 0.5 * OXBOW_WOBBLE * Math.sin(5.0 * signed + 2.0 * phase));
            double across = Math.abs(Math.sqrt(dx * dx + dz * dz) - bent) - half;
            double along = radius * (Math.abs(signed) - arm);
            clearance = Math.min(clearance, Math.max(0.0, Math.max(across, along)));
        }
        return clearance;
    }

    /**
     * Height the alluvial fans in reach add to the land at a position: most at a fan's apex, where its river leaves the
     * steep ground, thinning out to nothing at its rim, within a sector opening downstream. Where fans overlap, the
     * thickest counts.
     */
    double fanDeposit(double x, double z, double spread) {
        double cosine = Math.cos(Math.toRadians(spread));
        double deposit = 0.0;
        for (int f = 0; f < fans.length; f += FAN_STRIDE) {
            double radius = fans[f + 4];
            double dx = x - fans[f];
            double dz = z - fans[f + 1];
            if (Math.abs(dx) >= radius || Math.abs(dz) >= radius) {
                continue;
            }
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance >= radius) {
                continue;
            }
            // Close to the apex every direction counts as downstream, or the sector would come to a sharp point there
            double round = APEX_ROUND * radius;
            double along = (dx * fans[f + 2] + dz * fans[f + 3] + round) / (distance + round);
            double sector = smooth((along - cosine) / (1.0 - cosine));
            // The rim wanders in and out with the direction, in lobes, rather than running round as a circle
            double angle = Math.atan2(dx * fans[f + 3] - dz * fans[f + 2], dx * fans[f + 2] + dz * fans[f + 3]);
            double phase = fans[f] * PHASE_X + fans[f + 1] * PHASE_Z;
            double reach = radius * (1.0 - LOBES * (0.5 + 0.35 * Math.sin(3.0 * angle + phase) + 0.15 * Math.sin(7.0 * angle + 2.0 * phase)));
            if (distance >= reach) {
                continue;
            }
            double left = 1.0 - distance / reach;
            deposit = Math.max(deposit, fans[f + 5] * left * left * sector);
        }
        return deposit;
    }


    /**
     * Water level at one end of a segment, lowered onto the ground the first time it is needed: most segments of a
     * region are never queried, and the ground under each one can take erosion of a region of its own. A segment starts
     * no higher than the segments a bounded way above it end, so the value only depends on the river around the segment,
     * which every region holding it builds alike, and threads racing to fill it in store the same thing.
     */
    private double level(int segment, int end) {
        if (ground == null) {
            return segments[segment * STRIDE + 8 + end];
        }
        int slot = 2 * segment + end;
        long bits = lowered.get(slot);
        if (bits != NAN_BITS) {
            return Double.longBitsToDouble(bits);
        }
        double level;
        if (end == 0) {
            level = grounded(segment, 0);
            int above = upstream[segment];
            for (int k = config.fitReach * config.curveSegments; k > 0 && above >= 0; k--) {
                level = Math.min(level, grounded(above, 1));
                above = upstream[above];
            }
        } else {
            level = Math.min(grounded(segment, 1), level(segment, 0));
        }
        // Never below the lake the river runs into: lowered onto eroded ground lower than the lake, it met the lake a
        // step below its surface, and the lake stood up to four blocks above it at the shore
        double floor = floor(segment);
        if (!Double.isNaN(floor)) {
            level = Math.max(level, Math.min(floor, segments[segment * STRIDE + 8 + end]));
        }
        lowered.set(slot, Double.doubleToRawLongBits(level));
        return level;
    }

    /** Surface of the lake a segment ends in or runs into within rivers.lakeBackwater nodes, or NaN if there is none. */
    private double floor(int segment) {
        long bits = floors.get(segment);
        if (bits != NAN_BITS) {
            return Double.longBitsToDouble(bits);
        }
        double floor = Double.NEGATIVE_INFINITY;
        int below = segment;
        for (int k = config.lakeBackwater * config.curveSegments; k >= 0 && below >= 0; k--) {
            double surface = lake.applyAsDouble(segments[below * STRIDE + 2], segments[below * STRIDE + 3]);
            if (!Double.isNaN(surface)) {
                floor = surface;
                break;
            }
            below = downstream[below];
        }
        floors.set(segment, Double.doubleToRawLongBits(floor));
        return floor == Double.NEGATIVE_INFINITY ? Double.NaN : floor;
    }

    /** Water level at one end of a segment, lowered onto the ground there, before looking upstream. */
    private double grounded(int segment, int end) {
        int s = segment * STRIDE;
        int slot = 2 * segment + end;
        long bits = grounded.get(slot);
        if (bits != NAN_BITS) {
            return Double.longBitsToDouble(bits);
        }
        double level;
        if ((classes[segment] & POOL) != 0) {
            // A pool stays level from end to end, but no higher than the ground half way along it. Kept at the level it
            // was routed at, a pool on a steep stream stood thirty blocks and more above the eroded slope, and its banks
            // held the land up around it in a round dome hundreds of blocks across, with the stream in a slot cut through
            // it. Lowered to the ground at its lower end instead, it sank its upper half into a trench as deep
            level = Math.min(Math.max(segments[s + 8], segments[s + 9]),
                    ground.applyAsDouble(0.5 * (segments[s] + segments[s + 2]), 0.5 * (segments[s + 1] + segments[s + 3])));
        } else {
            level = Math.min(segments[s + 8 + end], ground.applyAsDouble(segments[s + 2 * end], segments[s + 1 + 2 * end]));
        }
        grounded.set(slot, Double.doubleToRawLongBits(level));
        return level;
    }

    /** Whether this region holds exactly the same segments as another. */
    public boolean contentEquals(RiverRegion other) {
        return originX == other.originX && originZ == other.originZ && Arrays.equals(segments, other.segments)
                && Arrays.equals(classes, other.classes) && Arrays.equals(upstream, other.upstream) && Arrays.deepEquals(index, other.index) && Arrays.equals(fans, other.fans)
                && Arrays.equals(oxbows, other.oxbows);
    }
}
