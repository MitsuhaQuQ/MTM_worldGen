package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Water erosion of the mountains and hills: valleys cut by the water that runs down them, with gullies feeding
 * ravines feeding valleys, and ridges branching between them, the way real ranges look.
 * <p>
 * Each logic region erodes a grid over itself and a margin around it, starting from the terrain skeleton with a
 * little noise on it so that water has somewhere to branch. Every round the land rises in proportion to its height,
 * water spreads downhill over the lower neighbors and adds up into catchments, each cell cuts down toward its steepest
 * neighbor the more water it carries (the stream power law, solved implicitly so that large steps stay stable), and
 * slopes creep. Rise and cut come to balance in a branching drainage net. The result is scaled back to the skeleton's
 * heights over a wide area, and only the difference is kept, as a texture on the skeleton. Erosion depends on
 * everything upstream, so neighboring regions disagree a little near their borders: the margin keeps that small, and a
 * band along the borders blends the two.
 */
final class ErosionLayer {
    interface HeightSampler {
        /** Land before erosion: the terrain skeleton without texture. */
        double height(double x, double z);

        /** Water level of a river channel at a position, or NaN where there is none. */
        double riverLevel(double x, double z);

        /** Water level of a lake at a position, or NaN where there is none. */
        double lakeLevel(double x, double z);

        /**
         * Makes ready whatever sampling an area needs, on the calling thread, and holds it until the returned action
         * runs. Sampling then runs on the logic threads, and anything it had to compute there could wait on a
         * computation that needs those same threads.
         */
        Runnable prepare(double minX, double minZ, double maxX, double maxZ);
    }

    private static final long SEED_SALT = 0x45524F5349L;
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final double[] DISTANCE = {Math.sqrt(2), 1, Math.sqrt(2), 1, 1, Math.sqrt(2), 1, Math.sqrt(2)};
    /** Points per side where a stored region's starting land is checked against the land now. */
    private static final int CHECKS = 4;
    /** Regions waiting to be computed ahead, and how long the background thread waits while generation computes one. */
    private static final int PREFETCH_QUEUE = 16;
    private static final long PREFETCH_PAUSE = 20;
    /** Points along each piece of a creek's course tried before the nearest is refined. */
    private static final int CURVE_STEPS = 6;
    /** Side creeks no longer than this many cells before they join another are left out. */
    private static final int STUB_CELLS = 2;

    private final WorldgenSettings settings;
    private final WorldgenSettings.Erosion config;
    private final HeightSampler sampler;
    private final Fbm roughness;
    private final Fbm warpX;
    private final Fbm warpZ;
    private final RegionCache<Eroded> regions;
    private volatile ErosionStore store;
    private final ThreadLocal<CachedRegion> last = new ThreadLocal<>();
    private final int cells;
    private final int margin;
    private final int size;
    /** Index step to each of the eight neighbors of a grid cell. */
    private final int[] offset;

    ErosionLayer(long seed, WorldgenSettings settings, HeightSampler sampler) {
        this.settings = settings;
        this.config = settings.erosion;
        this.sampler = sampler;
        this.roughness = new Fbm(Hashing.derive(seed, SEED_SALT), 3, config.roughnessWavelength);
        this.warpX = new Fbm(Hashing.derive(seed, SEED_SALT + 1), 2, config.warpWavelength);
        this.warpZ = new Fbm(Hashing.derive(seed, SEED_SALT + 2), 2, config.warpWavelength);
        this.cells = settings.general.regionSize / config.cellSize;
        this.margin = config.margin / config.cellSize;
        this.size = cells + 2 * margin;
        this.offset = new int[8];
        for (int d = 0; d < 8; d++) {
            offset[d] = DZ[d] * size + DX[d];
        }
        this.regions = new RegionCache<>(this::load, settings.general.regionCacheSize);
    }

    /** Keeps eroded regions in a store from now on, or in memory only if it is null. */
    void setStore(ErosionStore store) {
        this.store = store;
    }

    /** Makes sure a region is eroded, and stored if there is a store. */
    void precompute(int regionX, int regionZ) {
        regions.get(regionX, regionZ);
    }

    /**
     * One region's erosion over its window: the height change of each cell, and the creeks, as the water each cell
     * gathers (zero where too little for a creek) and the neighbor it drains to (an index into DX and DZ, or -1).
     */
    record Eroded(float[] change, float[] creek, byte[] down) {
    }

    private record CachedRegion(int regionX, int regionZ, Eroded eroded) {
    }

    /**
     * The creek nearest a position, from the gullies erosion drained: how far its course is, the water it carries
     * there, which way it runs, and the offset from the position to the nearest point of its course, all in blocks.
     */
    record Creek(double distance, double area, double alongX, double alongZ, double toX, double toZ) {
    }

    /**
     * Nearest creek within a cell or so of a position, or null. Creeks follow the steepest descent between cell
     * centers, read through the same warp as the height change, so they lie along the gullies it carved. Near a region
     * border each position takes its own region's creeks, as the gullies of the two solutions do not line up.
     */
    Creek creek(double blockX, double blockZ, double warpShare) {
        double warp = config.warp * warpShare;
        double x = blockX + warp * warpX.sample(blockX, blockZ);
        double z = blockZ + warp * warpZ.sample(blockX, blockZ);
        int regionSize = settings.general.regionSize;
        int regionX = Math.floorDiv((int) Math.floor(x), regionSize);
        int regionZ = Math.floorDiv((int) Math.floor(z), regionSize);
        Eroded eroded = cached(regionX, regionZ);
        double u = (x - (double) regionX * regionSize) / config.cellSize + margin - 0.5;
        double v = (z - (double) regionZ * regionSize) / config.cellSize + margin - 0.5;
        int centerI = (int) Math.round(u);
        int centerJ = (int) Math.round(v);
        double best = Double.MAX_VALUE;
        Creek nearest = null;
        double[] curve = new double[6];
        for (int j = Math.max(1, centerJ - 2); j <= Math.min(size - 2, centerJ + 2); j++) {
            for (int i = Math.max(1, centerI - 2); i <= Math.min(size - 2, centerI + 2); i++) {
                int index = j * size + i;
                float area = eroded.creek[index];
                if (area <= 0.0F || eroded.down[index] < 0 || !course(eroded, index, curve)) {
                    continue;
                }
                // Nearest point of the curve: the closest of a few points along it, then refined around that one
                double bestT = 0.0;
                double bestSquared = Double.MAX_VALUE;
                for (int k = 0; k <= CURVE_STEPS; k++) {
                    double squared = squaredDistance(curve, (double) k / CURVE_STEPS, u, v);
                    if (squared < bestSquared) {
                        bestSquared = squared;
                        bestT = (double) k / CURVE_STEPS;
                    }
                }
                double step = 0.5 / CURVE_STEPS;
                for (int round = 0; round < 4; round++, step *= 0.5) {
                    for (double t : new double[]{bestT - step, bestT + step}) {
                        double clamped = Math.max(0.0, Math.min(1.0, t));
                        double squared = squaredDistance(curve, clamped, u, v);
                        if (squared < bestSquared) {
                            bestSquared = squared;
                            bestT = clamped;
                        }
                    }
                }
                if (bestSquared >= best) {
                    continue;
                }
                best = bestSquared;
                double t = bestT;
                double s = 1.0 - t;
                double pointU = s * s * curve[0] + 2.0 * s * t * i + t * t * curve[4];
                double pointV = s * s * curve[1] + 2.0 * s * t * j + t * t * curve[5];
                double alongU = s * (i - curve[0]) + t * (curve[4] - i);
                double alongV = s * (j - curve[1]) + t * (curve[5] - j);
                double length = Math.max(1e-9, Math.hypot(alongU, alongV));
                float next = eroded.creek[index + offset[eroded.down[index]]];
                double carried = next > 0.0F ? area + t * (next - area) : area;
                nearest = new Creek(Math.sqrt(bestSquared) * config.cellSize, carried, alongU / length, alongV / length,
                        (pointU - u) * config.cellSize, (pointV - v) * config.cellSize);
            }
        }
        return nearest;
    }

    /**
     * The course of a creek past one cell, in grid coordinates, as a curve bent by the cell's center: it starts half way
     * from the cell its main source comes from (or at the center where it rises) and ends half way to the next cell, so
     * that neighboring pieces meet smoothly. A side creek ends instead on the course of the creek it joins, rather than
     * half way to a cell center that course bends away from. Fills start and end into curve[0..1] and curve[4..5].
     */
    private boolean course(Eroded eroded, int index, double[] curve) {
        int i = index % size;
        int j = index / size;
        int from = mainSource(eroded, index);
        curve[2] = i;
        curve[3] = j;
        curve[0] = from < 0 ? i : 0.5 * (i + from % size);
        curve[1] = from < 0 ? j : 0.5 * (j + from / size);
        int next = index + offset[eroded.down[index]];
        int nextI = next % size;
        int nextJ = next / size;
        if (eroded.creek[next] <= 0.0F || eroded.down[next] < 0 || mainSource(eroded, next) == index) {
            curve[4] = eroded.creek[next] <= 0.0F || eroded.down[next] < 0 ? nextI : 0.5 * (i + nextI);
            curve[5] = eroded.creek[next] <= 0.0F || eroded.down[next] < 0 ? nextJ : 0.5 * (j + nextJ);
            return true;
        }
        // The middle of the next cell's own curve, taking its end as half way on, which it is for most cells
        int nextFrom = mainSource(eroded, next);
        int after = next + offset[eroded.down[next]];
        double startI = 0.5 * (nextI + nextFrom % size);
        double startJ = 0.5 * (nextJ + nextFrom / size);
        curve[4] = 0.25 * startI + 0.5 * nextI + 0.25 * 0.5 * (nextI + after % size);
        curve[5] = 0.25 * startJ + 0.5 * nextJ + 0.25 * 0.5 * (nextJ + after / size);
        return true;
    }

    /** The neighbor draining into a creek cell that brings it the most water, or -1 where the creek rises. */
    private int mainSource(Eroded eroded, int index) {
        return mainSource(eroded.creek, eroded.down, index);
    }

    private int mainSource(float[] creek, byte[] down, int index) {
        int source = -1;
        float most = 0.0F;
        for (int d = 0; d < 8; d++) {
            int neighbor = index + offset[d];
            // The neighbor drains back the opposite way, which is direction 7 - d in this order
            if (down[neighbor] == 7 - d && creek[neighbor] > most) {
                most = creek[neighbor];
                source = neighbor;
            }
        }
        return source;
    }

    private static double squaredDistance(double[] curve, double t, double u, double v) {
        double s = 1.0 - t;
        // The control point is the cell center, which the caller passes through curve[2..3]
        double pointU = s * s * curve[0] + 2.0 * s * t * curve[2] + t * t * curve[4];
        double pointV = s * s * curve[1] + 2.0 * s * t * curve[3] + t * t * curve[5];
        return (pointU - u) * (pointU - u) + (pointV - v) * (pointV - v);
    }

    private Eroded cached(int regionX, int regionZ) {
        CachedRegion cached = last.get();
        if (cached == null || cached.regionX != regionX || cached.regionZ != regionZ) {
            cached = new CachedRegion(regionX, regionZ, regions.get(regionX, regionZ));
            last.set(cached);
        }
        return cached.eroded;
    }

    /** Height erosion takes off at a position: zero or less. */
    double change(double blockX, double blockZ, double warpShare) {
        // Read the grid through a gentle warp, so valleys wind instead of following the grid's eight directions.
        // Rivers hold their valleys in place, so the warp lets go close to them
        double warp = config.warp * warpShare;
        double x = blockX + warp * warpX.sample(blockX, blockZ);
        double z = blockZ + warp * warpZ.sample(blockX, blockZ);
        int regionSize = settings.general.regionSize;
        int regionX = Math.floorDiv((int) Math.floor(x), regionSize);
        int regionZ = Math.floorDiv((int) Math.floor(z), regionSize);
        double localX = x - (double) regionX * regionSize;
        double localZ = z - (double) regionZ * regionSize;
        double band = config.blend;
        // Near a border, blend with the neighbor's own solution, which covers this position through its margin
        double weightX = localX < band ? 0.5 - 0.5 * smooth(localX / band) : localX > regionSize - band ? 0.5 * smooth((localX - regionSize + band) / band) : 0.0;
        double weightZ = localZ < band ? 0.5 - 0.5 * smooth(localZ / band) : localZ > regionSize - band ? 0.5 * smooth((localZ - regionSize + band) / band) : 0.0;
        int stepX = localX < regionSize / 2.0 ? -1 : 1;
        int stepZ = localZ < regionSize / 2.0 ? -1 : 1;
        double value = (1.0 - weightX) * (1.0 - weightZ) * sample(regionX, regionZ, x, z);
        if (weightX > 0.0) {
            value += weightX * (1.0 - weightZ) * sample(regionX + stepX, regionZ, x, z);
        }
        if (weightZ > 0.0) {
            value += (1.0 - weightX) * weightZ * sample(regionX, regionZ + stepZ, x, z);
        }
        if (weightX > 0.0 && weightZ > 0.0) {
            value += weightX * weightZ * sample(regionX + stepX, regionZ + stepZ, x, z);
        }
        return value;
    }

    /**
     * One region's change at a position within its window, interpolated between cell centers. Cubic, since linear
     * interpolation leaves the slope jumping at every cell edge, which shows as facets a cell wide.
     */
    private double sample(int regionX, int regionZ, double x, double z) {
        float[] change = cached(regionX, regionZ).change;
        double u = (x - (double) regionX * settings.general.regionSize) / config.cellSize + margin - 0.5;
        double v = (z - (double) regionZ * settings.general.regionSize) / config.cellSize + margin - 0.5;
        int i = Math.max(1, Math.min(size - 3, (int) Math.floor(u)));
        int j = Math.max(1, Math.min(size - 3, (int) Math.floor(v)));
        double fu = Math.max(0.0, Math.min(1.0, u - i));
        double fv = Math.max(0.0, Math.min(1.0, v - j));
        double[] rows = new double[4];
        for (int k = 0; k < 4; k++) {
            int row = (j - 1 + k) * size + i;
            rows[k] = cubic(change[row - 1], change[row], change[row + 1], change[row + 2], fu);
        }
        return cubic(rows[0], rows[1], rows[2], rows[3], fv);
    }

    /** Catmull-Rom spline through four evenly spaced values, between the middle two. */
    private static double cubic(double a, double b, double c, double d, double t) {
        return b + 0.5 * t * (c - a + t * (2.0 * a - 5.0 * b + 4.0 * c - d + t * (3.0 * (b - c) + d - a)));
    }

    /**
     * Regions erosion is likely to need next, newest first: the neighbors of each region it had to compute while
     * something waited. Players move on into them, and chunks near a border blend in the region across it, so the
     * background thread computes them before anything waits again. Only the newest few are kept, as the oldest are
     * behind whoever asked for them.
     */
    private final ArrayDeque<long[]> ahead = new ArrayDeque<>();
    private final AtomicInteger waiting = new AtomicInteger();
    private Thread prefetcher;

    private void prefetchAround(int regionX, int regionZ) {
        synchronized (ahead) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) {
                        ahead.addFirst(new long[]{regionX + dx, regionZ + dz});
                    }
                }
            }
            while (ahead.size() > PREFETCH_QUEUE) {
                ahead.removeLast();
            }
            if (prefetcher == null) {
                prefetcher = new Thread(this::prefetch, "MTM erosion prefetch");
                prefetcher.setDaemon(true);
                prefetcher.setPriority(Thread.MIN_PRIORITY);
                prefetcher.start();
            }
            ahead.notifyAll();
        }
    }

    private void prefetch() {
        while (true) {
            long[] next;
            try {
                synchronized (ahead) {
                    while (ahead.isEmpty()) {
                        ahead.wait();
                    }
                    next = ahead.removeFirst();
                }
                // Generation comes first: the logic threads are shared, and a region that is waited for should not
                // queue behind one that might be
                while (waiting.get() > 0) {
                    Thread.sleep(PREFETCH_PAUSE);
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                regions.get((int) next[0], (int) next[1]);
            } catch (RuntimeException e) {
                // Whoever needs the region computes it again and sees the failure
            }
        }
    }

    /** A region's erosion from the store when it holds one for the land here, or computed and then stored. */
    private Eroded load(int regionX, int regionZ) {
        if (Thread.currentThread() == prefetcher) {
            return loadNow(regionX, regionZ);
        }
        waiting.incrementAndGet();
        try {
            return loadNow(regionX, regionZ);
        } finally {
            waiting.decrementAndGet();
            // Only for a world, which is what has a store: tests and maps jump about and would only make it compute
            // regions nobody asks for, one thread for each logic
            if (store != null) {
                prefetchAround(regionX, regionZ);
            }
        }
    }

    private Eroded loadNow(int regionX, int regionZ) {
        ErosionStore current = store;
        if (current == null) {
            return compute(regionX, regionZ);
        }
        double[] checks = checks(regionX, regionZ);
        Eroded stored = current.read(regionX, regionZ, checks, size * size);
        if (stored != null) {
            return stored;
        }
        Eroded eroded = compute(regionX, regionZ);
        current.write(regionX, regionZ, checks, eroded);
        return eroded;
    }

    /** The land erosion starts from, at a few points spread over a region. */
    private double[] checks(int regionX, int regionZ) {
        int regionSize = settings.general.regionSize;
        double[] checks = new double[CHECKS * CHECKS];
        for (int i = 0; i < CHECKS; i++) {
            for (int j = 0; j < CHECKS; j++) {
                checks[i * CHECKS + j] = sampler.height((regionX + (i + 0.5) / CHECKS) * regionSize, (regionZ + (j + 0.5) / CHECKS) * regionSize);
            }
        }
        return checks;
    }

    private Eroded compute(int regionX, int regionZ) {
        int n = size * size;
        double originX = (double) regionX * settings.general.regionSize - (double) margin * config.cellSize;
        double originZ = (double) regionZ * settings.general.regionSize - (double) margin * config.cellSize;
        double[] start = new double[n];
        double[] rough = new double[n];
        double[] river = new double[n];
        boolean[] lake = new boolean[n];
        Runnable release = sampler.prepare(originX, originZ, originX + size * config.cellSize, originZ + size * config.cellSize);
        try {
            LogicThreads.forEach(size, row -> {
                for (int col = 0; col < size; col++) {
                    double x = originX + (col + 0.5) * config.cellSize;
                    double z = originZ + (row + 0.5) * config.cellSize;
                    double noise = roughness.sample(x, z);
                    rough[row * size + col] = noise;
                    start[row * size + col] = sampler.height(x, z) + config.roughness * noise;
                    double lakeLevel = sampler.lakeLevel(x, z);
                    lake[row * size + col] = !Double.isNaN(lakeLevel);
                    river[row * size + col] = lake[row * size + col] ? lakeLevel : sampler.riverLevel(x, z);
                }
            });
        } finally {
            release.run();
        }
        double sea = settings.general.seaLevel - 1;
        double[] height = Arrays.copyOf(start, n);
        double[] uplift = new double[n];
        // Rivers and lakes are where the slopes drain to, like the sea: they keep their water level and neither rise
        // nor wear. Valleys grow down to the rivers that are there, so in the mountains the river valleys are simply the
        // valleys of the mountains
        boolean[] fixed = new boolean[n];
        for (int index = 0; index < n; index++) {
            if (!Double.isNaN(river[index])) {
                fixed[index] = true;
                height[index] = river[index];
            } else {
                uplift[index] = config.uplift * Math.max(0.0, start[index] - sea);
            }
        }
        int[] receiver = new int[n];
        double[] receiverDistance = new double[n];
        int[] stack = new int[n];
        int[] donorCount = new int[n];
        int[] donorStart = new int[n + 1];
        int[] donors = new int[n];
        double[] area = new double[n];
        double[] share = new double[8];
        double[] before = new double[n];
        double cellArea = (double) config.cellSize * config.cellSize;
        int top = 0;
        for (int round = 0; round < config.rounds; round++) {
            // The land rises where the skeleton stands high; the sea and the window's rim stay where they are
            LogicThreads.forEach(size - 2, line -> {
                int row = line + 1;
                for (int col = 1; col < size - 1; col++) {
                    height[row * size + col] += uplift[row * size + col];
                }
            });
            // Steepest descent; the sea and the window's rim keep their height and take in water. Each row only
            // reads heights, so rows run in parallel
            LogicThreads.forEach(size, row -> {
                for (int col = 0; col < size; col++) {
                    int index = row * size + col;
                    receiver[index] = index;
                    receiverDistance[index] = 1.0;
                    if (fixed[index] || height[index] < sea || col == 0 || row == 0 || col == size - 1 || row == size - 1) {
                        continue;
                    }
                    double steepest = 0.0;
                    for (int d = 0; d < 8; d++) {
                        int other = (row + DZ[d]) * size + col + DX[d];
                        double slope = (height[index] - height[other]) / DISTANCE[d];
                        if (slope > steepest) {
                            steepest = slope;
                            receiver[index] = other;
                            receiverDistance[index] = DISTANCE[d] * config.cellSize;
                        }
                    }
                }
            });
            Arrays.fill(donorCount, 0);
            for (int index = 0; index < n; index++) {
                if (receiver[index] != index) {
                    donorCount[receiver[index]]++;
                }
            }
            donorStart[0] = 0;
            for (int index = 0; index < n; index++) {
                donorStart[index + 1] = donorStart[index] + donorCount[index];
            }
            int[] fill = Arrays.copyOf(donorStart, n);
            for (int index = 0; index < n; index++) {
                if (receiver[index] != index) {
                    donors[fill[receiver[index]]++] = index;
                }
            }
            // Cells ordered from where water ends up to where it starts, so each comes after the cell it drains to
            top = 0;
            for (int index = 0; index < n; index++) {
                if (receiver[index] == index) {
                    stack[top++] = index;
                }
            }
            for (int read = 0; read < top; read++) {
                int cell = stack[read];
                for (int k = donorStart[cell]; k < donorStart[cell + 1]; k++) {
                    stack[top++] = donors[k];
                }
            }
            // Water spreads over all lower neighbors, by slope: one receiver each would draw channels along the
            // eight grid directions in parallel lines. Cells hand their water on from the highest down
            Arrays.fill(area, cellArea);
            boolean fourth = config.spreadExponent == 4.0;
            for (int k = top - 1; k >= 0; k--) {
                int cell = stack[k];
                if (receiver[cell] == cell) {
                    continue;
                }
                double here = height[cell];
                double total = 0.0;
                for (int d = 0; d < 8; d++) {
                    double drop = here - height[cell + offset[d]];
                    double weight = 0.0;
                    if (drop > 0.0) {
                        double slope = drop / DISTANCE[d];
                        double square = slope * slope;
                        weight = fourth ? square * square : power(slope, config.spreadExponent);
                        total += weight;
                    }
                    share[d] = weight;
                }
                double water = area[cell];
                for (int d = 0; d < 8; d++) {
                    if (share[d] > 0.0) {
                        area[cell + offset[d]] += water * share[d] / total;
                    }
                }
            }
            // Implicit stream power step: the more water, the closer a cell comes down to its receiver
            for (int k = 0; k < top; k++) {
                int cell = stack[k];
                int down = receiver[cell];
                if (down == cell) {
                    continue;
                }
                double factor = config.strength * power(area[cell], config.areaExponent) / receiverDistance[cell];
                double lowered = (height[cell] + factor * height[down]) / (1.0 + factor);
                height[cell] = Math.max(lowered, height[down]);
            }
            // Creep rounds off ridges and fills gullies that carry too little water to keep cutting
            System.arraycopy(height, 0, before, 0, n);
            LogicThreads.forEach(size - 2, line -> {
                int row = line + 1;
                for (int col = 1; col < size - 1; col++) {
                    int index = row * size + col;
                    if (fixed[index] || before[index] < sea) {
                        continue;
                    }
                    double laplacian = before[index - 1] + before[index + 1] + before[index - size] + before[index + size] - 4.0 * before[index];
                    height[index] = before[index] + config.diffusion * laplacian;
                }
            });
        }
        // Each cell's base: the level of the river or sea its water ends up in, or the window's rim. Bases follow the
        // drainage from where it ends, and are smoothed a little so they do not step across the divides between rivers
        double[] base = new double[n];
        for (int k = 0; k < top; k++) {
            int cell = stack[k];
            int down = receiver[cell];
            base[cell] = down != cell ? base[down] : fixed[cell] ? river[cell] : Math.min(height[cell], Math.max(sea, start[cell]));
        }
        base = blur(base, Math.max(1, (int) Math.round(config.baseSmoothing / config.cellSize)));
        // Scale the relief above the bases back to the skeleton's, over a wide area: the valleys stay, the ranges keep
        // the heights they were designed with, and nothing sinks below the river it drains to
        double[] eroded = new double[n];
        double[] designed = new double[n];
        for (int index = 0; index < n; index++) {
            eroded[index] = Math.max(0.0, height[index] - base[index]);
            designed[index] = Math.max(0.0, start[index] - config.roughness * rough[index] - base[index]);
        }
        int radius = Math.max(1, (int) Math.round(config.normalizeRadius / config.cellSize));
        double[] erodedMean = blur(eroded, radius);
        double[] designedMean = blur(designed, radius);
        float[] change = new float[n];
        for (int index = 0; index < n; index++) {
            if (lake[index]) {
                // Lakes fill their own basins, which erosion leaves as they are
                continue;
            }
            double land = start[index] - config.roughness * rough[index];
            double relief = Math.max(0.0, land - sea);
            double scaled = base[index] + (erodedMean[index] > 1.0 ? eroded[index] * designedMean[index] / erodedMean[index] : designed[index]);
            double difference = scaled - land;
            change[index] = (float) Math.max(-config.maxDepth * relief, Math.min(config.maxRaise * relief, difference));
        }
        // Creeks: the cells that gather enough water, down the steepest descent of the last round. They end where they
        // reach a river, a lake or the sea, which have creeks of their own
        float[] creek = new float[n];
        byte[] down = new byte[n];
        Arrays.fill(down, (byte) -1);
        for (int index = 0; index < n; index++) {
            if (fixed[index] || height[index] < sea || receiver[index] == index || area[index] < config.creekArea) {
                continue;
            }
            for (int d = 0; d < 8; d++) {
                if (index + offset[d] == receiver[index]) {
                    creek[index] = (float) area[index];
                    down[index] = (byte) d;
                    break;
                }
            }
        }
        // Side creeks that rise right beside the creek they join would only be stubs sticking out of it
        for (int pass = 0; pass < STUB_CELLS; pass++) {
            List<Integer> stubs = new ArrayList<>();
            for (int index = 0; index < n; index++) {
                if (creek[index] > 0.0F && mainSource(creek, down, index) < 0) {
                    int next = index + offset[down[index]];
                    if (creek[next] > 0.0F && mainSource(creek, down, next) != index) {
                        stubs.add(index);
                    }
                }
            }
            for (int index : stubs) {
                creek[index] = 0.0F;
                down[index] = -1;
            }
        }
        pruneStraightCreeks(creek, down);
        return new Eroded(change, creek, down);
    }

    /**
     * Leaves out creeks that run their whole course as one straight line: no other creek joins them, and most of
     * their cells drain the same way. Erosion on an even slope only carves rows of parallel gullies down it, and
     * creeks in them ran as straight ditches side by side for hundreds of blocks. Creeks of a branching network keep
     * all their branches, however straight. Each creek is judged from its mouth, so the result does not depend on
     * the order the cells are visited in.
     */
    private void pruneStraightCreeks(float[] creek, byte[] down) {
        int n = creek.length;
        byte[] inflows = new byte[n];
        for (int index = 0; index < n; index++) {
            if (creek[index] > 0.0F) {
                int next = index + offset[down[index]];
                if (creek[next] > 0.0F) {
                    inflows[next]++;
                }
            }
        }
        int[] directions = new int[8];
        List<Integer> line = new ArrayList<>();
        for (int mouth = 0; mouth < n; mouth++) {
            if (creek[mouth] <= 0.0F || creek[mouth + offset[down[mouth]]] > 0.0F) {
                continue;
            }
            line.clear();
            Arrays.fill(directions, 0);
            boolean branches = false;
            for (int index = mouth; index >= 0 && !branches; index = mainSource(creek, down, index)) {
                branches = inflows[index] > 1;
                line.add(index);
                directions[down[index]]++;
            }
            int most = Arrays.stream(directions).max().orElse(0);
            if (!branches && line.size() >= config.creekStraightCells && most >= config.creekStraightShare * line.size()) {
                for (int index : line) {
                    creek[index] = 0.0F;
                    down[index] = -1;
                }
            }
        }
    }

    /** Box blur of a grid, twice over, which comes close to a Gaussian of about this radius. */
    private double[] blur(double[] values, int radius) {
        double[] result = values;
        for (int pass = 0; pass < 2; pass++) {
            result = blurAxis(blurAxis(result, radius, 1, size), radius, size, 1);
        }
        return result;
    }

    private double[] blurAxis(double[] values, int radius, int step, int lineStep) {
        double[] out = new double[values.length];
        for (int line = 0; line < size; line++) {
            int base = line * lineStep;
            double sum = 0.0;
            int count = 0;
            for (int k = 0; k <= radius && k < size; k++) {
                sum += values[base + k * step];
                count++;
            }
            for (int k = 0; k < size; k++) {
                out[base + k * step] = sum / count;
                int add = k + radius + 1;
                int remove = k - radius;
                if (add < size) {
                    sum += values[base + add * step];
                    count++;
                }
                if (remove >= 0) {
                    sum -= values[base + remove * step];
                    count--;
                }
            }
        }
        return out;
    }

    /** Math.pow, with the common exponents worked out directly: it runs millions of times per region. */
    private static double power(double value, double exponent) {
        if (exponent == 0.5) {
            return Math.sqrt(value);
        } else if (exponent == 1.0) {
            return value;
        } else if (exponent == 2.0) {
            return value * value;
        } else if (exponent == 4.0) {
            double square = value * value;
            return square * square;
        }
        return Math.pow(value, exponent);
    }

    private static double smooth(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }
}
