package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

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

    private static final long OCEAN_GROUP = 0L;
    /** Plates searched in each direction around the containing plate cell. */
    private static final int PLATE_SEARCH = 2;
    private static final int PLATE_WINDOW = PLATE_SEARCH * 2 + 1;
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
    private final WorldgenSettings settings;
    private final WorldgenSettings.Continents config;
    private final double minGradient;
    private final double threshold;
    private final double high;
    private final double low;

    ContinentLayer(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.config = settings.continents;
        this.plateSeed = Hashing.derive(seed, PLATE_SALT);
        this.groupSeed = Hashing.derive(seed, GROUP_SALT);
        this.warpX = new Fbm(Hashing.derive(seed, WARP_X_SALT), 3, config.warpWavelength);
        this.warpZ = new Fbm(Hashing.derive(seed, WARP_Z_SALT), 3, config.warpWavelength);
        this.detail = new Fbm(Hashing.derive(seed, DETAIL_SALT), 4, config.detailWavelength);
        this.straits = new Fbm(Hashing.derive(seed, STRAIT_SALT), 2, config.straitWavelength);
        // Floor for the gradient in the distance estimate, half the typical plate gradient: inland the gradient
        // can approach zero, and dividing by it made the estimate jump by tens of blocks per block
        this.minGradient = 0.5 / config.plateSpacing;

        NoiseStats stats = new NoiseStats(this::value, THRESHOLD_SAMPLES_PER_AXIS, config.plateSpacing / THRESHOLD_SAMPLES_PER_PLATE);
        this.threshold = stats.quantile(config.oceanRatio);
        this.high = stats.quantile(HIGH_QUANTILE);
        this.low = stats.quantile(LOW_QUANTILE);
    }

    double value(double x, double z) {
        double wx = x + config.warpAmplitude * warpX.sample(x, z);
        double wz = z + config.warpAmplitude * warpZ.sample(x, z);
        double strait = config.straitMean + config.straitVariation * straits.sample(x, z);
        return plateValue(wx / config.plateSpacing, wz / config.plateSpacing, strait) + config.detailAmplitude * detail.sample(x, z);
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
        return baseAboveSea / config.landRange * (high - threshold) * config.plateSpacing;
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
        return sea - config.oceanDepth * Math.min(1.0, (threshold - c) / (threshold - low));
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
                long hash = Hashing.hash(plateSeed, cellU + du, cellV + dv);
                double pu = cellU + du + 0.5 + config.plateJitter * (Hashing.unit(hash) - 0.5);
                double pv = cellV + dv + 0.5 + config.plateJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5);
                distances[count] = Math.hypot(u - pu, v - pv);
                boolean ocean = Hashing.unit(Hashing.derive(hash, OCEAN_PLATE_SALT)) < config.oceanPlateFraction;
                groups[count] = ocean ? OCEAN_GROUP : continentGroup(pu, pv);
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

}
