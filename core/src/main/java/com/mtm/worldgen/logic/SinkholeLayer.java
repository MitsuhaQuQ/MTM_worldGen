package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

/**
 * Collapse sinkholes in hills and mountains: a steep pit in the ground, with a cave chamber below it that the pit
 * opens into. The pit is part of the height map, so plants and snow treat it as ground; the chamber changes blocks only.
 */
final class SinkholeLayer {
    private static final long SINKHOLE_SALT = 0x53494E4BL;
    /** Slots of the per-thread site cache, a power of two. */
    private static final int CACHE_SIZE = 64;
    /** How far a site strays from the center of its cell, as a share of the cell. */
    private static final double JITTER = 0.8;
    /** Where the walls of a pit start, as a share of its radius. */
    private static final double WALL_START = 0.55;

    private final WorldgenSettings.Sinkholes config;
    private final TerrainLogic logic;
    private final long seed;
    private final Fbm warp;
    private final double reachFactor;
    private final ThreadLocal<Site[]> sites = ThreadLocal.withInitial(() -> new Site[CACHE_SIZE]);

    SinkholeLayer(long seed, WorldgenSettings settings, TerrainLogic logic) {
        this.config = settings.sinkholes;
        this.logic = logic;
        this.seed = Hashing.derive(seed, SINKHOLE_SALT);
        this.warp = new Fbm(Hashing.derive(seed, SINKHOLE_SALT + 1), 2, config.warpWavelength);
        this.reachFactor = Math.max(1.0 + config.warp, config.chamberRadius);
    }

    /** The sinkhole whose pit or chamber may reach a position, or null. */
    Site near(double x, double z) {
        // Sites stay well inside their cells, so most positions have only their own cell to look at
        double reach = config.radiusMax * reachFactor;
        Site[] cache = sites.get();
        int lastX = (int) Math.floor((x + reach) / config.spacing);
        int lastZ = (int) Math.floor((z + reach) / config.spacing);
        for (int cellX = (int) Math.floor((x - reach) / config.spacing); cellX <= lastX; cellX++) {
            for (int cellZ = (int) Math.floor((z - reach) / config.spacing); cellZ <= lastZ; cellZ++) {
                Site site = site(cache, cellX, cellZ);
                if (site.radius > 0.0 && Math.hypot(x - site.x, z - site.z) <= site.radius * reachFactor) {
                    return site;
                }
            }
        }
        return null;
    }

    /** Land at a position with any pit sunk into it. */
    double lower(double x, double z, double land) {
        Site site = near(x, z);
        if (site == null) {
            return land;
        }
        double u = Math.hypot(x - site.x, z - site.z) / (site.radius * (1.0 + config.warp * warp.sample(x, z)));
        double t = Math.max(0.0, Math.min(1.0, (u - WALL_START) / (1.0 - WALL_START)));
        double open = 1.0 - t * t * (3.0 - 2.0 * t);
        return land > site.floor ? land - (land - site.floor) * open : land;
    }

    /** Whether a position lies on the walls or floor of a pit. */
    boolean contains(double x, double z) {
        Site site = near(x, z);
        return site != null && Math.hypot(x - site.x, z - site.z) <= site.radius * (1.0 + config.warp);
    }

    /** The pit of a cell as x, floor height, z, or null. */
    double[] pit(int cellX, int cellZ) {
        Site site = site(sites.get(), cellX, cellZ);
        return site.radius > 0.0 ? new double[]{site.x, site.floor, site.z} : null;
    }

    /** How many blocks inside the chamber of a sinkhole a block lies; negative outside it. */
    double chamberDepth(Site site, int x, int y, int z) {
        double wide = site.radius * config.chamberRadius * (1.0 + config.warp * warp.sample(x, z));
        double high = y < site.chamberY ? config.chamberHeight : config.chamberHeight + 4.0;
        return (1.0 - Math.hypot(Math.hypot(x - site.x, z - site.z) / wide, (y - site.chamberY) / high)) * config.chamberHeight;
    }

    private Site site(Site[] cache, int cellX, int cellZ) {
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
        if (Hashing.unit(Hashing.derive(hash, 1)) >= config.chance) {
            return new Site(cellX, cellZ, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double x = (cellX + 0.5 + JITTER * (Hashing.unit(hash) - 0.5)) * config.spacing;
        double z = (cellZ + 0.5 + JITTER * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * config.spacing;
        double top = logic.sinkholeGround(x, z);
        if (Double.isNaN(top)) {
            return new Site(cellX, cellZ, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
        double radius = config.radiusMin + Hashing.unit(Hashing.derive(hash, 2)) * (config.radiusMax - config.radiusMin);
        double depth = config.depthMin + Hashing.unit(Hashing.derive(hash, 3)) * (config.depthMax - config.depthMin);
        double floor = top - depth;
        return new Site(cellX, cellZ, x, z, radius, floor, floor - 0.6 * config.chamberHeight);
    }

    record Site(int cellX, int cellZ, double x, double z, double radius, double floor, double chamberY) {
    }
}
