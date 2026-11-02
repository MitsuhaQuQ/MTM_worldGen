package com.mtm.worldgen.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

/**
 * Snapshot of the chunks that MTM did not generate, and how new terrain around them closes in on them. Terrain then
 * depends on the seed, the position, the settings and this snapshot, but never on the order chunks are explored in,
 * because chunks MTM generates later do not join it. Immutable once built, apart from the cache of lattice points.
 */
public final class ForeignMap {
    /** A chunk has samples at these block offsets per axis, edges included, so they meet the seam exactly. */
    public static final int[] SAMPLE_OFFSETS = {0, 5, 10, 15};
    public static final int SAMPLES = SAMPLE_OFFSETS.length * SAMPLE_OFFSETS.length;
    /** A height meaning that a sample has no water. */
    public static final short NO_WATER = Short.MIN_VALUE;
    public static final ForeignMap EMPTY = new ForeignMap(Map.of(), new WorldgenSettings.Foreign());

    private static final int CHUNK = 16;
    /** Influence is computed on a lattice of this spacing and interpolated. */
    private static final int LATTICE = 8;
    /** Edge samples are indexed in buckets of this size. */
    private static final int BUCKET = 256;
    private static final double SOFTEN = 4.0;
    /** How far along an edge the highest point counts toward the width of a blend. */
    private static final double PEAK_REACH = 192.0;
    private static final Point NOTHING = new Point(Double.MAX_VALUE, 0.0, Double.NaN, 0.0, null, false);

    /** One foreign chunk. Upgraded chunks came from an older MTM, and blend further because more is known about them. */
    public record Chunk(short[] ground, short[] water, String biome, boolean upgraded) {
    }

    /** How strongly a foreign edge claims a position: blend share, ground and water to close in on, biome there. */
    public record Influence(double share, double distance, double ground, double water, String biome, boolean upgraded) {
    }

    private record Edge(double x, double z, double ground, double water, String biome, boolean upgraded) {
    }

    private record Point(double distance, double ground, double water, double peak, String biome, boolean upgraded) {
    }

    private final Map<Long, Chunk> chunks;
    private final WorldgenSettings.Foreign config;
    private final Map<Long, List<Edge>> buckets = new HashMap<>();
    private final Map<Long, Point> lattice = new ConcurrentHashMap<>();
    private final int bucketReach;

    public ForeignMap(Map<Long, Chunk> chunks, WorldgenSettings.Foreign config) {
        this.chunks = Map.copyOf(chunks);
        this.config = config;
        this.bucketReach = (int) Math.ceil(Math.max(config.maxWidth, config.upgradeMaxWidth) / BUCKET) + 1;
        this.chunks.forEach(this::addEdges);
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public Map<Long, Chunk> chunks() {
        return chunks;
    }

    public boolean contains(int chunkX, int chunkZ) {
        return chunks.containsKey(key(chunkX, chunkZ));
    }

    /** Whether a position lies in a foreign chunk. */
    boolean covers(double x, double z) {
        return contains((int) Math.floor(x / CHUNK), (int) Math.floor(z / CHUNK));
    }

    private void addEdges(long key, Chunk chunk) {
        int chunkX = (int) (key >> 32);
        int chunkZ = (int) key;
        int last = SAMPLE_OFFSETS.length - 1;
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j <= last; j++) {
                boolean open = i == 0 && !contains(chunkX - 1, chunkZ) || i == last && !contains(chunkX + 1, chunkZ)
                        || j == 0 && !contains(chunkX, chunkZ - 1) || j == last && !contains(chunkX, chunkZ + 1);
                if (open) {
                    int index = j * SAMPLE_OFFSETS.length + i;
                    // A block stands for its whole square, so the seam itself lies on the outer side of an edge block
                    double x = chunkX * CHUNK + (i == last ? CHUNK : SAMPLE_OFFSETS[i]);
                    double z = chunkZ * CHUNK + (j == last ? CHUNK : SAMPLE_OFFSETS[j]);
                    double water = chunk.water[index] == NO_WATER ? Double.NaN : chunk.water[index];
                    buckets.computeIfAbsent(key(Math.floorDiv((int) x, BUCKET), Math.floorDiv((int) z, BUCKET)), k -> new ArrayList<>())
                            .add(new Edge(x, z, chunk.ground[index], water, chunk.biome, chunk.upgraded));
                }
            }
        }
    }

    /**
     * Influence at a position, or null if nothing foreign is in reach.
     *
     * @param land smooth land there, which the width of the blend depends on: the higher the step, the wider it runs
     */
    Influence influence(double x, double z, double land) {
        int cellX = Math.floorDiv((int) Math.floor(x), LATTICE);
        int cellZ = Math.floorDiv((int) Math.floor(z), LATTICE);
        double tx = (x - cellX * LATTICE) / LATTICE;
        double tz = (z - cellZ * LATTICE) / LATTICE;
        Point a = point(cellX, cellZ);
        Point b = point(cellX + 1, cellZ);
        Point c = point(cellX, cellZ + 1);
        Point d = point(cellX + 1, cellZ + 1);
        if (a == NOTHING || b == NOTHING || c == NOTHING || d == NOTHING) {
            return null;
        }
        double distance = bilinear(a.distance, b.distance, c.distance, d.distance, tx, tz);
        double peak = bilinear(a.peak, b.peak, c.peak, d.peak, tx, tz);
        double ground = bilinear(a.ground, b.ground, c.ground, d.ground, tx, tz);
        double most = a.upgraded ? config.upgradeMaxWidth : config.maxWidth;
        double step = Math.max(Math.abs(peak - land), Math.abs(ground - land));
        double width = Math.max(config.minWidth, Math.min(most, step / config.grade));
        if (distance >= width) {
            return null;
        }
        double t = 1.0 - distance / width;
        return new Influence(t * t * (3.0 - 2.0 * t), distance, ground, a.water, a.biome, a.upgraded);
    }

    private static double bilinear(double a, double b, double c, double d, double tx, double tz) {
        double top = a + (b - a) * tx;
        return top + (c + (d - c) * tx - top) * tz;
    }

    private Point point(int cellX, int cellZ) {
        return lattice.computeIfAbsent(key(cellX, cellZ), key -> computePoint(cellX * LATTICE, cellZ * LATTICE));
    }

    /**
     * Inverse distance weights give the exact height right at the seam, and turn into a wide average further out, or
     * the ridges and valleys of the edge would run straight out across the blend.
     */
    private Point computePoint(double x, double z) {
        int bucketX = Math.floorDiv((int) x, BUCKET);
        int bucketZ = Math.floorDiv((int) z, BUCKET);
        double nearest = Double.MAX_VALUE;
        Edge closest = null;
        double groundSum = 0.0;
        double weightSum = 0.0;
        List<Edge> near = new ArrayList<>();
        for (int dx = -bucketReach; dx <= bucketReach; dx++) {
            for (int dz = -bucketReach; dz <= bucketReach; dz++) {
                List<Edge> edges = buckets.get(key(bucketX + dx, bucketZ + dz));
                if (edges == null) {
                    continue;
                }
                for (Edge edge : edges) {
                    double distance = Math.hypot(edge.x - x, edge.z - z);
                    double weight = 1.0 / (Math.pow(distance + SOFTEN, 4.0));
                    groundSum += weight * edge.ground;
                    weightSum += weight;
                    if (distance < nearest) {
                        nearest = distance;
                        closest = edge;
                    }
                    if (distance < 2.0 * PEAK_REACH + nearest) {
                        near.add(edge);
                    }
                }
            }
        }
        if (closest == null) {
            return NOTHING;
        }
        double peak = closest.ground;
        for (Edge edge : near) {
            if (Math.hypot(edge.x - closest.x, edge.z - closest.z) < PEAK_REACH) {
                peak = Math.max(peak, edge.ground);
            }
        }
        return new Point(covers(x + 0.5, z + 0.5) ? 0.0 : nearest, groundSum / weightSum, closest.water, peak, closest.biome, closest.upgraded);
    }
}
