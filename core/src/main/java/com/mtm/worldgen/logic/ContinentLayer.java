package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

import java.util.Arrays;

/**
 * Pipeline step 1: continent and ocean layout. A pure function of seed + block coordinates.
 *
 * <p>The world is split into jittered Voronoi plates. Each plate is either a continent or an open ocean;
 * the continent value rises with distance from the plate border, so plate borders form a connected
 * network of seas between continents, and ocean plates form open oceans. Domain warping and a detail
 * octave make coastlines irregular. The land/ocean threshold is a per-seed quantile, so the ocean
 * fraction matches the configured ratio.
 */
final class ContinentLayer {
    private static final long PLATE_SALT = 0x504C415445L;
    private static final long GROUP_SALT = 0x47524F5550L;
    private static final long OCEAN_PLATE_SALT = 0x4F4345414EL;
    private static final long WARP_X_SALT = 0x5741525058L;
    private static final long WARP_Z_SALT = 0x574152505AL;
    private static final long DETAIL_SALT = 0x44455441494CL;
    private static final long STRAIT_SALT = 0x535452414954L;
    private static final long ISLAND_SALT = 0x49534C414E44L;
    private static final long MOTION_SALT = 0x4D4F54494F4EL;
    private static final long CHARACTER_SALT = 0x43484152L;
    /** Slowest plate drift, as a share of the fastest. */
    private static final double MIN_DRIFT = 0.4;
    private static final int ISLAND_CACHE_SIZE = 256;
    /** Beyond the waterline, how far around an island still counts as its shore, as a share of its radius. */
    private static final double ISLAND_REACH = 1.3;
    /** How far land keeps clear of an island, as a share of its radius, so that hills ease off gently. */
    private static final double ISLAND_CLEARANCE = 3.0;

    private static final long OCEAN_GROUP = 0L;
    /** Plates searched in each direction around the containing plate cell. */
    private static final int PLATE_SEARCH = 2;
    private static final int PLATE_WINDOW = PLATE_SEARCH * 2 + 1;
    /**
     * Plates searched for belts and plate character, which reach further than coasts do: a plate a little more than a
     * belt's reach beyond the nearest one still counts, and one missing from a smaller window would make the terrain jump.
     */
    private static final int BELT_SEARCH = 3;
    private static final int BELT_WINDOW = BELT_SEARCH * 2 + 1;
    /** Slots of the per-thread plate cache, a power of two. */
    private static final int PLATE_CACHE_SIZE = 256;
    /**
     * Largest |plate value|. Plates outside the search window are at least about this far away, so capping
     * here hides the window edge and keeps the value continuous.
     */
    private static final double PLATE_VALUE_CAP = 1.2;


    private static final int THRESHOLD_SAMPLES_PER_AXIS = 128;
    /** Samples per plate along an axis, so the statistics span many plates at any plate size. */
    private static final double THRESHOLD_SAMPLES_PER_PLATE = 8.0;
    /** Quantiles used as the "highest land" and "deepest ocean" values when normalizing heights. */
    private static final double HIGH_QUANTILE = 0.995;
    private static final double LOW_QUANTILE = 0.005;

    /** Finite difference step in blocks for the continent value gradient. */
    private static final double GRADIENT_STEP = 16.0;
    private static final double MAX_COAST_DISTANCE = 4096.0;


    private final long plateSeed;
    private final long groupSeed;
    private final Fbm warpX;
    private final Fbm warpZ;
    private final Fbm detail;
    private final Fbm straits;
    private final Fbm islandShore;
    private final WorldgenSettings settings;
    private final WorldgenSettings.Continents config;
    private final double minGradient;
    private final double plateSpacing;
    private final double threshold;
    private final double high;
    private final double low;
    private final ThreadLocal<Plate[]> plates = ThreadLocal.withInitial(() -> new Plate[PLATE_CACHE_SIZE]);
    /**
     * The last belts looked up on each thread, {x, z, width, result...} each: a column asks for them several times, at
     * its own position and at the warped one its ridges are looked up at, which would push each other out of one slot.
     */
    private final ThreadLocal<double[][]> lastBelts = ThreadLocal.withInitial(() -> {
        double[][] last = new double[BELT_MEMO][3 + BELT_VALUES];
        for (double[] slot : last) {
            slot[0] = Double.NaN;
        }
        return last;
    });
    private static final int BELT_MEMO = 4;
    private final ThreadLocal<int[]> nextBelt = ThreadLocal.withInitial(() -> new int[1]);
    /** Values {@link #belt} returns. */
    static final int BELT_VALUES = 7;
    private final ThreadLocal<Island[]> islands = ThreadLocal.withInitial(() -> new Island[ISLAND_CACHE_SIZE]);
    private final long islandSeed;
    private boolean islandsReady;

    ContinentLayer(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.config = settings.continents;
        double size = settings.scale.continents;
        this.plateSpacing = config.plateSpacing * size;
        this.plateSeed = Hashing.derive(seed, PLATE_SALT);
        this.groupSeed = Hashing.derive(seed, GROUP_SALT);
        this.islandSeed = Hashing.derive(seed, ISLAND_SALT);
        this.warpX = new Fbm(Hashing.derive(seed, WARP_X_SALT), 3, config.warpWavelength * size);
        this.warpZ = new Fbm(Hashing.derive(seed, WARP_Z_SALT), 3, config.warpWavelength * size);
        this.detail = new Fbm(Hashing.derive(seed, DETAIL_SALT), 4, config.detailWavelength * size);
        this.straits = new Fbm(Hashing.derive(seed, STRAIT_SALT), 2, config.straitWavelength * size);
        this.islandShore = new Fbm(Hashing.derive(seed, ISLAND_SALT + 1), 3, settings.islands.shoreWavelength);
        // Floor for the gradient in the distance estimate, half the typical plate gradient: inland the gradient
        // can approach zero, and dividing by it made the estimate jump by tens of blocks per block
        this.minGradient = 0.5 / plateSpacing;

        NoiseStats stats = new NoiseStats(this::mainlandValue, THRESHOLD_SAMPLES_PER_AXIS, plateSpacing / THRESHOLD_SAMPLES_PER_PLATE);
        this.threshold = stats.quantile(config.oceanRatio);
        this.high = stats.quantile(HIGH_QUANTILE);
        this.low = stats.quantile(LOW_QUANTILE);
        this.islandsReady = true;
    }

    double value(double x, double z) {
        double value = mainlandValue(x, z);
        // Thresholds are measured on the mainland alone, while they are still unset
        return islandsReady && value < threshold ? Math.max(value, islandValue(x, z)) : value;
    }

    private double mainlandValue(double x, double z) {
        double wx = x + config.warpAmplitude * warpX.sample(x, z);
        double wz = z + config.warpAmplitude * warpZ.sample(x, z);
        double strait = config.straitMean + config.straitVariation * straits.sample(x, z);
        return plateValue(wx / plateSpacing, wz / plateSpacing, strait) + config.detailAmplitude * detail.sample(x, z);
    }

    /** Highest island value at a position: a dome above the land threshold over each island's radius. */
    private double islandValue(double x, double z) {
        double spacing = settings.islands.spacing;
        int cellX = (int) Math.floor(x / spacing);
        int cellZ = (int) Math.floor(z / spacing);
        double best = -Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Island island = island(cellX + dx, cellZ + dz);
                if (island.kind != null) {
                    double reach = island.radius * (1.0 + settings.islands.shoreVariation * islandShore.sample(x, z));
                    double u = Math.hypot(x - island.x, z - island.z) / reach;
                    best = Math.max(best, threshold + island.rise * (1.0 - u * u));
                }
            }
        }
        return best;
    }

    /** How far a position is clear of islands: 0 on one, rising smoothly to 1 at the edge of its shore. */
    double islandClearance(double x, double z) {
        double spacing = settings.islands.spacing;
        int cellX = (int) Math.floor(x / spacing);
        int cellZ = (int) Math.floor(z / spacing);
        double clearance = 1.0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Island island = island(cellX + dx, cellZ + dz);
                if (island.kind != null) {
                    double u = Math.hypot(x - island.x, z - island.z) / island.radius;
                    double t = Math.max(0.0, Math.min(1.0, (u - 1.0) / (ISLAND_CLEARANCE - 1.0)));
                    clearance = Math.min(clearance, t * t * (3.0 - 2.0 * t));
                }
            }
        }
        return clearance;
    }

    /** Kind of the island around a position, or null away from islands. */
    IslandKind islandKind(double x, double z) {
        double spacing = settings.islands.spacing;
        int cellX = (int) Math.floor(x / spacing);
        int cellZ = (int) Math.floor(z / spacing);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Island island = island(cellX + dx, cellZ + dz);
                if (island.kind != null && Math.hypot(x - island.x, z - island.z) < island.radius * ISLAND_REACH) {
                    return island.kind;
                }
            }
        }
        return null;
    }

    private Island island(int cellX, int cellZ) {
        Island[] cache = islands.get();
        int slot = (cellX * 31 + cellZ) & (ISLAND_CACHE_SIZE - 1);
        Island island = cache[slot];
        if (island == null || island.cellX != cellX || island.cellZ != cellZ) {
            island = createIsland(cellX, cellZ);
            cache[slot] = island;
        }
        return island;
    }

    private Island createIsland(int cellX, int cellZ) {
        WorldgenSettings.Islands islandConfig = settings.islands;
        long hash = Hashing.hash(islandSeed, cellX, cellZ);
        double x = (cellX + 0.5 + 0.8 * (Hashing.unit(hash) - 0.5)) * islandConfig.spacing;
        double z = (cellZ + 0.5 + 0.8 * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * islandConfig.spacing;
        if (Hashing.unit(Hashing.derive(hash, 2)) >= islandConfig.chance) {
            return new Island(cellX, cellZ, x, z, 1.0, 0.0, null);
        }
        double roll = Hashing.unit(Hashing.derive(hash, 3));
        IslandKind kind = roll < islandConfig.sandShare ? IslandKind.SAND
                : roll < islandConfig.sandShare + islandConfig.rockShare ? IslandKind.ROCK : IslandKind.MUSHROOM;
        double offshore = (threshold - mainlandValue(x, z)) * plateSpacing;
        double radius = islandConfig.radiusMin + Hashing.unit(Hashing.derive(hash, 4)) * (islandConfig.radiusMax - islandConfig.radiusMin);
        double margin = kind == IslandKind.MUSHROOM ? islandConfig.mushroomMargin : islandConfig.landMargin;
        if (offshore < margin + ISLAND_CLEARANCE * radius) {
            return new Island(cellX, cellZ, x, z, 1.0, 0.0, null);
        }
        double height = settings.heightScale() * (kind == IslandKind.ROCK ? islandConfig.rockHeight : islandConfig.sandHeight);
        return new Island(cellX, cellZ, x, z, radius, height / config.landRange * (high - threshold), kind);
    }

    /**
     * Horizontal distance to the coastline in blocks at a position with the given continent value: positive
     * inland, negative offshore. Uses a first-order estimate (value - threshold) / |gradient|, which is accurate
     * near the coast where beaches need it, and is clamped further away where the gradient can vanish.
     */
    double coastDistance(double x, double z, double value) {
        double gx = (value(x + GRADIENT_STEP, z) - value(x - GRADIENT_STEP, z)) / (2.0 * GRADIENT_STEP);
        double gz = (value(x, z + GRADIENT_STEP) - value(x, z - GRADIENT_STEP)) / (2.0 * GRADIENT_STEP);
        double gradient = Math.max(Math.hypot(gx, gz), minGradient);
        double distance = (value - threshold) / gradient;
        return Math.max(-MAX_COAST_DISTANCE, Math.min(MAX_COAST_DISTANCE, distance));
    }

    /**
     * Rough distance inland in blocks from a continent height. The value rises steadily away from coasts and the
     * height follows it, which makes this cheap enough for every column, unlike {@link #coastDistance}.
     */
    double inlandDistance(double baseAboveSea) {
        return baseAboveSea / config.landRange * (high - threshold) * plateSpacing;
    }

    /**
     * Surface height of the continent layer for a continent value. Continuous and with finite slope at the
     * coastline: the coarse grid samples this function, so any jump or steep kink there shows up as
     * grid-aligned stair steps along the coast.
     */
    double height(double c) {
        double sea = settings.general.seaLevel - 1;
        if (c >= threshold) {
            return sea + config.landRange * Math.min(1.0, (c - threshold) / (high - threshold));
        }
        // Past the shelf the open ocean deepens: offshore distance comes from the value as inland distance does
        double offshore = (threshold - c) * plateSpacing;
        double abyss = Math.max(0.0, Math.min(1.0, (offshore - config.shelfWidth) / config.slopeWidth));
        return sea - config.oceanDepth * Math.min(1.0, (threshold - c) / (threshold - low))
                - config.abyssDepth * abyss * abyss * (3.0 - 2.0 * abyss);
    }

    /**
     * Signed distance-like value in plate units: positive on continents, negative in oceans, zero on coasts.
     *
     * <p>Every continent plate belongs to a continent group (the nearest site of a coarser jittered grid).
     * Plates of the same group merge into one landmass; borders between groups and borders with ocean plates
     * become coasts, so groups are separated by a connected network of straits that joins the open oceans.
     * The distance to such a border is approximated by (d_other - d_own) / 2. Values are capped, which keeps
     * the function continuous although only a bounded neighborhood of plates is searched.
     *
     * @param strait extra half-width of straits between continent groups, in plate units; negative values
     *               close the strait into a land bridge
     */
    private double plateValue(double u, double v, double strait) {
        int cellU = (int) Math.floor(u);
        int cellV = (int) Math.floor(v);
        int count = 0;
        double[] distances = new double[PLATE_WINDOW * PLATE_WINDOW];
        long[] groups = new long[PLATE_WINDOW * PLATE_WINDOW];
        int owner = 0;
        for (int du = -PLATE_SEARCH; du <= PLATE_SEARCH; du++) {
            for (int dv = -PLATE_SEARCH; dv <= PLATE_SEARCH; dv++) {
                Plate plate = plate(cellU + du, cellV + dv);
                distances[count] = Math.hypot(u - plate.u, v - plate.v);
                groups[count] = plate.group;
                if (distances[count] < distances[owner]) {
                    owner = count;
                }
                count++;
            }
        }
        boolean ownerOcean = groups[owner] == OCEAN_GROUP;
        double nearestCoast = PLATE_VALUE_CAP;
        for (int i = 0; i < count; i++) {
            if (ownerOcean ? groups[i] == OCEAN_GROUP : groups[i] == groups[owner]) {
                continue;
            }
            double distance = (distances[i] - distances[owner]) * 0.5;
            boolean betweenGroups = !ownerOcean && groups[i] != OCEAN_GROUP;
            nearestCoast = Math.min(nearestCoast, betweenGroups ? distance - strait : distance);
        }
        return ownerOcean ? -nearestCoast : nearestCoast;
    }

    /**
     * Plates around a position, for the terrain tiers: {convergence, border direction x, border direction z, weight,
     * relief, highland, wide convergence}. Wide convergence is the same as convergence, reaching the wide width instead. Convergence is how fast the plates on either side move together, from -1 (apart) to 1,
     * weighted by closeness to the border, which falls to 0 at the given width in blocks. The direction runs along the
     * border. Relief and highland are each plate's own character, from -1 to 1, blended across the same width at its
     * borders, so that each plate holds one kind of land over its whole interior.
     *
     * <p>Every pair of nearby plates counts, through the larger of their two distances less the nearest one's: next to
     * the border of the nearest plate that is the distance to it, and where three plates meet, the border between the
     * other two joins in. The value only depends on distances, so it is continuous across borders and junctions.
     */
    double[] belt(double x, double z, double width, double wide) {
        double[][] memo = lastBelts.get();
        for (double[] slot : memo) {
            if (slot[0] == x && slot[1] == z && slot[2] == width + wide) {
                return Arrays.copyOfRange(slot, 3, 3 + BELT_VALUES);
            }
        }
        double[] result = computeBelt(x, z, width, wide);
        int[] next = nextBelt.get();
        double[] last = memo[next[0]];
        next[0] = (next[0] + 1) % BELT_MEMO;
        last[0] = x;
        last[1] = z;
        last[2] = width + wide;
        System.arraycopy(result, 0, last, 3, BELT_VALUES);
        return result;
    }

    private double[] computeBelt(double x, double z, double width, double wide) {
        double wx = x + config.warpAmplitude * warpX.sample(x, z);
        double wz = z + config.warpAmplitude * warpZ.sample(x, z);
        double u = wx / plateSpacing;
        double v = wz / plateSpacing;
        double reach = width / plateSpacing;
        double wideReach = Math.max(reach, wide / plateSpacing);
        int cellU = (int) Math.floor(u);
        int cellV = (int) Math.floor(v);
        Plate[] near = new Plate[BELT_WINDOW * BELT_WINDOW];
        double[] distances = new double[near.length];
        int count = 0;
        double nearest = Double.MAX_VALUE;
        for (int du = -BELT_SEARCH; du <= BELT_SEARCH; du++) {
            for (int dv = -BELT_SEARCH; dv <= BELT_SEARCH; dv++) {
                Plate plate = plate(cellU + du, cellV + dv);
                near[count] = plate;
                distances[count] = Math.hypot(u - plate.u, v - plate.v);
                nearest = Math.min(nearest, distances[count]);
                count++;
            }
        }
        // Distances are halved to the border, so plates further than twice the reach take no part
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (distances[i] - nearest < 2.0 * wideReach) {
                near[kept] = near[i];
                distances[kept] = distances[i];
                kept++;
            }
        }
        double convergence = 0.0;
        double wideConvergence = 0.0;
        double weightSum = 0.0;
        double cos2 = 0.0;
        double sin2 = 0.0;
        for (int i = 0; i < kept; i++) {
            for (int j = i + 1; j < kept; j++) {
                double border = 0.5 * (Math.max(distances[i], distances[j]) - nearest);
                if (border >= wideReach) {
                    continue;
                }
                double t = Math.min(1.0, border / reach);
                double weight = (1.0 - t * t) * (1.0 - t * t);
                double tw = border / wideReach;
                double wideWeight = (1.0 - tw * tw) * (1.0 - tw * tw);
                double nu = near[j].u - near[i].u;
                double nv = near[j].v - near[i].v;
                double length = Math.hypot(nu, nv);
                if (length <= 0.0) {
                    continue;
                }
                nu /= length;
                nv /= length;
                // Closing speed along the line between the two plates, from -1 to 1
                double closing = 0.5 * ((near[i].driftU - near[j].driftU) * nu + (near[i].driftV - near[j].driftV) * nv);
                convergence += weight * closing;
                wideConvergence += wideWeight * closing;
                weightSum += weight;
                // Directions along borders have no sign, so they are averaged at double angle
                cos2 += weight * (nv * nv - nu * nu);
                sin2 += weight * (-2.0 * nu * nv);
            }
        }
        double characterWeight = 0.0;
        double relief = 0.0;
        double highland = 0.0;
        for (int i = 0; i < kept; i++) {
            double t = Math.min(1.0, 0.5 * (distances[i] - nearest) / reach);
            double weight = 1.0 - t * t * (3.0 - 2.0 * t);
            characterWeight += weight;
            relief += weight * near[i].relief;
            highland += weight * near[i].highland;
        }
        relief /= characterWeight;
        highland /= characterWeight;
        if (weightSum <= 0.0) {
            return new double[]{0.0, 0.0, 0.0, 0.0, relief, highland, clamp(wideConvergence)};
        }
        double angle = 0.5 * Math.atan2(sin2, cos2);
        return new double[]{clamp(convergence), Math.cos(angle), Math.sin(angle), Math.min(1.0, weightSum), relief, highland, clamp(wideConvergence)};
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    /** Center and group of a plate. Plates are far larger than any sampling step, so nearly every lookup is a repeat. */
    private Plate plate(int cellU, int cellV) {
        Plate[] cache = plates.get();
        int slot = (cellU * 31 + cellV) & (PLATE_CACHE_SIZE - 1);
        Plate plate = cache[slot];
        if (plate == null || plate.cellU != cellU || plate.cellV != cellV) {
            long hash = Hashing.hash(plateSeed, cellU, cellV);
            double pu = cellU + 0.5 + config.plateJitter * (Hashing.unit(hash) - 0.5);
            double pv = cellV + 0.5 + config.plateJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5);
            boolean ocean = Hashing.unit(Hashing.derive(hash, OCEAN_PLATE_SALT)) < config.oceanPlateFraction;
            long motion = Hashing.derive(hash, MOTION_SALT);
            double heading = 2.0 * Math.PI * Hashing.unit(motion);
            double speed = MIN_DRIFT + (1.0 - MIN_DRIFT) * Hashing.unit(Hashing.mix(motion));
            long character = Hashing.derive(hash, CHARACTER_SALT);
            plate = new Plate(cellU, cellV, pu, pv, ocean ? OCEAN_GROUP : continentGroup(pu, pv), speed * Math.cos(heading), speed * Math.sin(heading),
                    // A plate is highland or not as a whole, which keeps it out of the threshold's reach and its plateau in
                    // one piece; plateaus are continental, so an ocean plate is never one
                    2.0 * Hashing.unit(character) - 1.0,
                    !ocean && Hashing.unit(Hashing.mix(character)) < settings.plateaus.plateChance ? 1.0 : -1.0);
            cache[slot] = plate;
        }
        return plate;
    }

    /** Id of the continent group a plate center belongs to: the nearest site of the group grid. */
    private long continentGroup(double pu, double pv) {
        double gu = pu / config.groupSpacing;
        double gv = pv / config.groupSpacing;
        int cellU = (int) Math.floor(gu);
        int cellV = (int) Math.floor(gv);
        double best = Double.MAX_VALUE;
        long group = 0L;
        for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
                long hash = Hashing.hash(groupSeed, cellU + du, cellV + dv);
                double su = cellU + du + 0.5 + config.plateJitter * (Hashing.unit(hash) - 0.5);
                double sv = cellV + dv + 0.5 + config.plateJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5);
                double d = (gu - su) * (gu - su) + (gv - sv) * (gv - sv);
                if (d < best) {
                    best = d;
                    group = hash;
                }
            }
        }
        return group;
    }

    enum IslandKind {
        SAND, ROCK, MUSHROOM
    }

    private record Island(int cellX, int cellZ, double x, double z, double radius, double rise, IslandKind kind) {
    }

    private record Plate(int cellU, int cellV, double u, double v, long group, double driftU, double driftV, double relief, double highland) {
    }
}
