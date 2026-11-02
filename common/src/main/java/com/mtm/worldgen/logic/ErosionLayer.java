package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.util.Arrays;

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

        /**
         * Makes ready whatever sampling an area needs, on the calling thread. Sampling then runs on the logic threads,
         * and anything it had to compute there could wait on a computation that needs those same threads.
         */
        void prepare(double minX, double minZ, double maxX, double maxZ);
    }

    private static final long SEED_SALT = 0x45524F5349L;
    private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DZ = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final double[] DISTANCE = {Math.sqrt(2), 1, Math.sqrt(2), 1, 1, Math.sqrt(2), 1, Math.sqrt(2)};

    private final WorldgenSettings settings;
    private final WorldgenSettings.Erosion config;
    private final HeightSampler sampler;
    private final Fbm roughness;
    private final Fbm warpX;
    private final Fbm warpZ;
    private final RegionCache<float[]> regions;
    private final ThreadLocal<CachedRegion> last = new ThreadLocal<>();
    private final int cells;
    private final int margin;
    private final int size;

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
        this.regions = new RegionCache<>(this::compute, settings.general.regionCacheSize);
    }

    private record CachedRegion(int regionX, int regionZ, float[] change) {
    }

    /** Height erosion takes off at a position: zero or less. */
    double change(double blockX, double blockZ) {
        // Read the grid through a gentle warp, so valleys wind instead of following the grid's eight directions
        double x = blockX + config.warp * warpX.sample(blockX, blockZ);
        double z = blockZ + config.warp * warpZ.sample(blockX, blockZ);
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
        CachedRegion cached = last.get();
        if (cached == null || cached.regionX != regionX || cached.regionZ != regionZ) {
            cached = new CachedRegion(regionX, regionZ, regions.get(regionX, regionZ));
            last.set(cached);
        }
        double u = (x - (double) regionX * settings.general.regionSize) / config.cellSize + margin - 0.5;
        double v = (z - (double) regionZ * settings.general.regionSize) / config.cellSize + margin - 0.5;
        int i = Math.max(1, Math.min(size - 3, (int) Math.floor(u)));
        int j = Math.max(1, Math.min(size - 3, (int) Math.floor(v)));
        double fu = Math.max(0.0, Math.min(1.0, u - i));
        double fv = Math.max(0.0, Math.min(1.0, v - j));
        float[] change = cached.change;
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

    private float[] compute(int regionX, int regionZ) {
        int n = size * size;
        double originX = (double) regionX * settings.general.regionSize - (double) margin * config.cellSize;
        double originZ = (double) regionZ * settings.general.regionSize - (double) margin * config.cellSize;
        double[] start = new double[n];
        double[] rough = new double[n];
        sampler.prepare(originX, originZ, originX + size * config.cellSize, originZ + size * config.cellSize);
        LogicThreads.forEach(size, row -> {
            for (int col = 0; col < size; col++) {
                double x = originX + (col + 0.5) * config.cellSize;
                double z = originZ + (row + 0.5) * config.cellSize;
                double noise = roughness.sample(x, z);
                rough[row * size + col] = noise;
                start[row * size + col] = sampler.height(x, z) + config.roughness * noise;
            }
        });
        double sea = settings.general.seaLevel - 1;
        double[] height = Arrays.copyOf(start, n);
        double[] uplift = new double[n];
        for (int index = 0; index < n; index++) {
            uplift[index] = config.uplift * Math.max(0.0, start[index] - sea);
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
                    if (height[index] < sea || col == 0 || row == 0 || col == size - 1 || row == size - 1) {
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
            int top = 0;
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
            for (int k = top - 1; k >= 0; k--) {
                int cell = stack[k];
                if (receiver[cell] == cell) {
                    continue;
                }
                int col = cell % size;
                int row = cell / size;
                double total = 0.0;
                for (int d = 0; d < 8; d++) {
                    int other = (row + DZ[d]) * size + col + DX[d];
                    double drop = height[cell] - height[other];
                    if (drop > 0.0) {
                        share[d] = power(drop / DISTANCE[d], config.spreadExponent);
                        total += share[d];
                    } else {
                        share[d] = 0.0;
                    }
                }
                for (int d = 0; d < 8; d++) {
                    if (share[d] > 0.0) {
                        area[(row + DZ[d]) * size + col + DX[d]] += area[cell] * share[d] / total;
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
                    if (before[index] < sea) {
                        continue;
                    }
                    double laplacian = before[index - 1] + before[index + 1] + before[index - size] + before[index + size] - 4.0 * before[index];
                    height[index] = before[index] + config.diffusion * laplacian;
                }
            });
        }
        // Scale the eroded land back to the skeleton's heights, over a wide area: the valleys stay, the ranges keep
        // the heights they were designed with
        double[] eroded = new double[n];
        double[] designed = new double[n];
        for (int index = 0; index < n; index++) {
            eroded[index] = Math.max(0.0, height[index] - sea);
            designed[index] = Math.max(0.0, start[index] - config.roughness * rough[index] - sea);
        }
        int radius = Math.max(1, (int) Math.round(config.normalizeRadius / config.cellSize));
        double[] erodedMean = blur(eroded, radius);
        double[] designedMean = blur(designed, radius);
        float[] change = new float[n];
        for (int index = 0; index < n; index++) {
            double relief = designed[index];
            double scaled = erodedMean[index] > 1.0 ? eroded[index] * designedMean[index] / erodedMean[index] : relief;
            double difference = scaled - relief;
            change[index] = (float) Math.max(-config.maxDepth * relief, Math.min(config.maxRaise * relief, difference));
        }
        return change;
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
