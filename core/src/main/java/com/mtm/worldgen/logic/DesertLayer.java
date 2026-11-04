package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

/**
 * Dunes and oases in the desert. Dunes are transverse ridges across one wind direction per world, a gentle slope up
 * the windward side and a steeper lee face, broken into separate dunes along their crests. An oasis is a small pond
 * with green land around it, in a flat hollow where the dunes give way. How much of the desert a position belongs to
 * comes from {@link TerrainLogic}, which also decides whether a site can hold an oasis.
 */
final class DesertLayer {
    private static final long DUNE_SALT = 0x44554E45L;
    private static final long OASIS_SALT = 0x4F415349L;
    /** Slots of the per-thread site cache, a power of two. */
    private static final int CACHE_SIZE = 16;
    /** How far a site strays from the center of its cell, as a share of the cell. */
    private static final double JITTER = 0.6;
    /** Share of its cell an oasis keeps clear of the cell's edge, so a position only ever has its own cell's oasis. */
    static final double SITE_MARGIN = 0.5 * (1.0 - JITTER);
    /** Grade of the banks rising from the water, and how far out, in pond radii, they go back to the land as it was. */
    private static final double BANK_GRADE = 0.2;
    private static final double BANK_REACH = 1.5;

    private final WorldgenSettings.Deserts config;
    private final TerrainLogic logic;
    private final long seed;
    private final double windX;
    private final double windZ;
    private final Fbm crestWarp;
    private final Fbm segments;
    private final Fbm fields;
    private final Fbm shore;
    private final Fbm green;
    private final ThreadLocal<Site[]> sites = ThreadLocal.withInitial(() -> new Site[CACHE_SIZE]);

    DesertLayer(long seed, WorldgenSettings settings, TerrainLogic logic) {
        this.config = settings.deserts;
        this.logic = logic;
        this.seed = Hashing.derive(seed, OASIS_SALT);
        // One wind for the whole world: a direction that turned with position would curl the ridges into swirls
        double angle = 2.0 * Math.PI * Hashing.unit(Hashing.derive(seed, DUNE_SALT));
        this.windX = Math.cos(angle);
        this.windZ = Math.sin(angle);
        this.crestWarp = new Fbm(Hashing.derive(seed, DUNE_SALT + 1), 2, config.crestWarpWavelength);
        this.segments = new Fbm(Hashing.derive(seed, DUNE_SALT + 2), 2, config.segmentWavelength);
        this.fields = new Fbm(Hashing.derive(seed, DUNE_SALT + 3), 2, config.fieldWavelength * settings.scale.biomes);
        this.shore = new Fbm(Hashing.derive(seed, OASIS_SALT + 1), 2, config.shoreWavelength);
        this.green = new Fbm(Hashing.derive(seed, OASIS_SALT + 2), 2, config.shoreWavelength * 4.0);
    }

    /** Share of dune fields at a position, from 0 between fields to 1 inside one. */
    double fieldShare(double x, double z) {
        return smooth((fields.sample(x, z) - config.fieldLow) / (config.fieldHigh - config.fieldLow));
    }

    /** Height of the dunes at a position above the troughs between them, at full strength. */
    double dunes(double x, double z) {
        double along = (x * windX + z * windZ + config.crestWarp * crestWarp.sample(x, z)) / config.duneWavelength;
        double phase = along - Math.floor(along);
        double windward = 1.0 - config.duneLeeShare;
        double profile = phase < windward ? smooth(phase / windward) : 1.0 - smooth((phase - windward) / config.duneLeeShare);
        double ridge = smooth((segments.sample(x, z) - config.segmentThreshold) / (0.5 - config.segmentThreshold));
        return config.duneHeight * ridge * profile;
    }

    /** The oasis whose cleared land reaches a position, or null. */
    Site oasis(double x, double z) {
        if (!config.oases) {
            return null;
        }
        int cellX = (int) Math.floor(x / config.oasisSpacing);
        int cellZ = (int) Math.floor(z / config.oasisSpacing);
        Site site = site(cellX, cellZ);
        return site.radius > 0.0 && Math.hypot(x - site.x, z - site.z) < site.radius * config.clearRadius ? site : null;
    }

    /** How much of the dunes an oasis clears at a position: all of them on its green land, none at the cleared radius. */
    double cleared(Site site, double x, double z) {
        double greenEdge = config.greenRadius * (1.0 + config.greenWarp);
        double u = Math.hypot(x - site.x, z - site.z) / site.radius;
        return 1.0 - smooth((u - greenEdge) / (config.clearRadius - greenEdge));
    }

    /** Distance from the middle of an oasis to a position, in pond radii, with the shore wandering. */
    private double pondDistance(Site site, double x, double z) {
        return Math.hypot(x - site.x, z - site.z) / (site.radius * (1.0 + config.shoreWarp * shore.sample(x, z)));
    }

    /** Whether a position lies in the pond of an oasis. */
    boolean inPond(Site site, double x, double z) {
        return pondDistance(site, x, z) < 1.0;
    }

    /** Whether a position lies on the green land of an oasis, its pond included. */
    boolean isGreen(Site site, double x, double z) {
        double u = Math.hypot(x - site.x, z - site.z) / (site.radius * config.greenRadius * (1.0 + config.greenWarp * green.sample(x, z)));
        return u < 1.0;
    }

    /**
     * Land with the pond of an oasis dug into it. Its banks rise gently from the water and meet the land a little way
     * out, so the shore is one block above the water: low enough for sugar cane, and with no wall around it.
     */
    double pond(Site site, double x, double z, double land) {
        double u = pondDistance(site, x, z);
        if (u < 1.0) {
            return Math.min(land, site.level - 0.5 - config.pondDepth * (1.0 - u * u));
        }
        double bank = Math.floor(site.level) + 0.5 + (u - 1.0) * site.radius * BANK_GRADE;
        if (land > bank) {
            return bank + (land - bank) * smooth((u - 1.0) / BANK_REACH);
        }
        return land + (bank - land) * (1.0 - smooth((u - BANK_REACH) / BANK_REACH));
    }

    private Site site(int cellX, int cellZ) {
        Site[] cache = sites.get();
        int slot = (cellX * 31 + cellZ) & (CACHE_SIZE - 1);
        Site site = cache[slot];
        if (site == null || site.cellX != cellX || site.cellZ != cellZ) {
            site = createSite(cellX, cellZ);
            cache[slot] = site;
        }
        return site;
    }

    private Site createSite(int cellX, int cellZ) {
        long hash = Hashing.hash(seed, cellX, cellZ);
        if (Hashing.unit(Hashing.derive(hash, 1)) >= config.oasisChance) {
            return new Site(cellX, cellZ, 0.0, 0.0, 0.0, 0.0);
        }
        double x = (cellX + 0.5 + JITTER * (Hashing.unit(hash) - 0.5)) * config.oasisSpacing;
        double z = (cellZ + 0.5 + JITTER * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * config.oasisSpacing;
        double radius = config.pondRadiusMin + Hashing.unit(Hashing.derive(hash, 2)) * (config.pondRadiusMax - config.pondRadiusMin);
        double ground = logic.oasisGround(x, z, radius * config.clearRadius);
        if (Double.isNaN(ground)) {
            return new Site(cellX, cellZ, 0.0, 0.0, 0.0, 0.0);
        }
        // The water stands half a block into a whole block, so the banks one block above it come out even
        return new Site(cellX, cellZ, x, z, radius, Math.floor(ground) - 0.5);
    }

    private static double smooth(double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    /** An oasis: the middle of its pond, its radius, and its water level. A radius of 0 means the cell has none. */
    record Site(int cellX, int cellZ, double x, double z, double radius, double level) {
    }
}
