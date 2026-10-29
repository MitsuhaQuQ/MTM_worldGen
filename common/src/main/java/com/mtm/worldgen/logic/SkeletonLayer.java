package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;
import com.mtm.worldgen.noise.RidgedNoise;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline step 3: terrain skeleton on land, added on top of the continent base. A pure function of
 * seed + block coordinates, evaluated per block column so ridges and peaks stay sharp.
 *
 * <ul>
 *   <li>Mountain sites sit on a jittered grid inside low-frequency orogeny zones. Each site is either part of
 *       a range or an isolated peak. Range sites link to the neighbor best aligned with a low-frequency
 *       orientation field, so neighboring ranges run roughly parallel and chain into long ridges.</li>
 *   <li>Mountains stand on a broad massif uplift of their zone. Cross sections are S-shaped, with the half-width
 *       derived from the slope limit; overlapping ridges merge through a smooth maximum, and a ridged texture
 *       stretched along the range adds spurs and gullies.</li>
 *   <li>Foothills form a belt around every range and peak.</li>
 *   <li>Plateaus are raised table lands in their own zones; hills cover a further zone with rolling terrain.</li>
 * </ul>
 * Every profile's worst-case grade is derived from the configured slope limits.
 */
final class SkeletonLayer {
    private static final long ZONE_SALT = 0x4F524F47454EL;
    private static final long SITE_SALT = 0x53495445L;
    private static final long ORIENTATION_SALT = 0x4F5249454E54L;
    private static final long RIDGE_SALT = 0x5249444745L;
    private static final long DETAIL_SALT = 0x524F434B59L;
    private static final long FOOTHILL_SALT = 0x464F4F54L;
    private static final long PLATEAU_ZONE_SALT = 0x504C4154L;
    private static final long PLATEAU_HEIGHT_SALT = 0x504C4854L;
    private static final long HILLS_ZONE_SALT = 0x48494C4C5AL;
    private static final long HILLS_SALT = 0x48494C4CL;

    // Mountain zones and sites
    private static final double ZONE_WAVELENGTH = 7168.0;
    /** Fraction of the world inside orogeny zones (roughly the share of land with mountains). */
    private static final double ZONE_COVERAGE = 0.32;
    private static final double ZONE_FADE = 0.05;
    private static final double SITE_SPACING = 1536.0;
    private static final double SITE_JITTER = 0.8;
    /**
     * Sites searched in each direction. A link spans up to about 2.8 cells (diagonal neighbor plus jitter), so
     * a point near one end of a ridge can be almost 3 cells from the site that owns it; a smaller window cuts
     * ridges off and leaves cliffs.
     */
    private static final int SITE_SEARCH = 3;
    /** Sites need at least this much zone weight to carry a mountain. */
    private static final double SITE_MIN_ZONE = 0.1;
    /** Broad uplift of the whole orogeny zone that ranges and peaks stand on. */
    private static final double MASSIF_HEIGHT = 70.0;
    /** Ridge and peak height above the massif. */
    private static final double PEAK_MIN = 60.0;
    private static final double PEAK_MAX = 170.0;
    private static final double RIDGE_WARP = 700.0;
    private static final double RIDGE_WARP_WAVELENGTH = 3072.0;
    /** Minimum cosine between a link and the orientation field for two range sites to join. */
    private static final double LINK_MIN_ALIGNMENT = 0.5;
    private static final double ORIENTATION_WAVELENGTH = 16384.0;
    private static final double RIDGE_WAVELENGTH = 1024.0;
    /** Ridge height varies along the range by this fraction, giving summits and saddles. */
    private static final double RIDGE_VARIATION = 0.2;
    /**
     * Spur-and-gully texture on mountain flanks: ridged noise stretched along the range orientation, with an
     * amplitude proportional to the local mountain height so it fades out at the foot.
     */
    private static final double TEXTURE_SHARE = 0.15;
    private static final double TEXTURE_ACROSS = 640.0;
    private static final double TEXTURE_ALONG = 1920.0;
    /** Share of the mountain slope limit used by the main profile; the rest is headroom for texture and foothills. */
    private static final double MOUNTAIN_SLOPE_SHARE = 0.45;
    /** Blend width in blocks where two mountain profiles meet, so overlapping ridges merge without a crease. */
    private static final double MERGE_WIDTH = 24.0;

    private static final double FOOTHILL_HEIGHT = 32.0;
    private static final double FOOTHILL_WAVELENGTH = 512.0;

    // Plateaus
    private static final double PLATEAU_ZONE_WAVELENGTH = 9216.0;
    private static final double PLATEAU_COVERAGE = 0.07;
    private static final double PLATEAU_FADE = 0.2;
    private static final double PLATEAU_MIN = 40.0;
    private static final double PLATEAU_MAX = 80.0;

    // Hills
    private static final double HILLS_ZONE_WAVELENGTH = 5120.0;
    private static final double HILLS_COVERAGE = 0.35;
    private static final double HILLS_FADE = 0.15;
    private static final double HILLS_HEIGHT = 26.0;
    private static final double HILLS_WAVELENGTH = 640.0;

    /**
     * Skeleton fades in while the continent base rises this many blocks above sea level, so mountains do not
     * rise straight out of the sea. Uses the smooth base height rather than the coast distance estimate,
     * which is only reliable close to the coast.
     */
    private static final double COAST_FADE_HEIGHT = 8.0;

    /** Maximum surface height the height constants in this class are designed for. */
    private static final double BASELINE_MAX_SURFACE = 300.0;

    private static final int STATS_SAMPLES_PER_AXIS = 96;
    private static final double STATS_SPACING = 1536.0;
    private static final int SITE_CACHE_LIMIT = 4096;

    private static final int NONE = 0;
    private static final int RANGE = 1;
    private static final int PEAK = 2;

    private final long siteSeed;
    private final Fbm zone;
    private final Fbm orientation;
    private final Fbm ridge;
    private final Fbm ridgeWarpX;
    private final Fbm ridgeWarpZ;
    private final RidgedNoise texture;
    private final Fbm foothills;
    private final Fbm plateauZone;
    private final Fbm plateauHeight;
    private final Fbm hillsZone;
    private final Fbm hills;
    private final WorldgenSettings settings;
    /**
     * Heights above (and the hill wavelengths that keep their slopes) are designed for a 300 block maximum
     * surface and scaled by maxSurfaceHeight / 300, as the design document specifies.
     */
    private final double scale;
    private final double mountainGrade;
    private final double zoneLow;
    private final double zoneHigh;
    private final double plateauLow;
    private final double plateauHigh;
    private final double hillsLow;
    private final double hillsHigh;
    private final ThreadLocal<Map<Long, Site>> sites = ThreadLocal.withInitial(HashMap::new);

    SkeletonLayer(long seed, WorldgenSettings settings) {
        this.settings = settings;
        this.scale = settings.maxSurfaceHeight() / BASELINE_MAX_SURFACE;
        this.siteSeed = Hashing.derive(seed, SITE_SALT);
        this.zone = new Fbm(Hashing.derive(seed, ZONE_SALT), 3, ZONE_WAVELENGTH);
        this.orientation = new Fbm(Hashing.derive(seed, ORIENTATION_SALT), 2, ORIENTATION_WAVELENGTH);
        this.ridge = new Fbm(Hashing.derive(seed, RIDGE_SALT), 2, RIDGE_WAVELENGTH);
        this.ridgeWarpX = new Fbm(Hashing.derive(seed, RIDGE_SALT + 1), 2, RIDGE_WARP_WAVELENGTH);
        this.ridgeWarpZ = new Fbm(Hashing.derive(seed, RIDGE_SALT + 2), 2, RIDGE_WARP_WAVELENGTH);
        this.texture = new RidgedNoise(Hashing.derive(seed, DETAIL_SALT), 3);
        this.foothills = new Fbm(Hashing.derive(seed, FOOTHILL_SALT), 3, FOOTHILL_WAVELENGTH * scale);
        this.plateauZone = new Fbm(Hashing.derive(seed, PLATEAU_ZONE_SALT), 3, PLATEAU_ZONE_WAVELENGTH);
        this.plateauHeight = new Fbm(Hashing.derive(seed, PLATEAU_HEIGHT_SALT), 2, PLATEAU_ZONE_WAVELENGTH);
        this.hillsZone = new Fbm(Hashing.derive(seed, HILLS_ZONE_SALT), 3, HILLS_ZONE_WAVELENGTH);
        this.hills = new Fbm(Hashing.derive(seed, HILLS_SALT), 3, HILLS_WAVELENGTH * scale);
        this.mountainGrade = settings.mountainSlope() / 100.0 * MOUNTAIN_SLOPE_SHARE;

        NoiseStats zoneStats = new NoiseStats(zone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.zoneLow = zoneStats.quantile(1.0 - ZONE_COVERAGE - ZONE_FADE);
        this.zoneHigh = zoneStats.quantile(1.0 - ZONE_COVERAGE + ZONE_FADE);
        NoiseStats plateauStats = new NoiseStats(plateauZone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.plateauLow = plateauStats.quantile(1.0 - PLATEAU_COVERAGE - PLATEAU_FADE / 2.0);
        this.plateauHigh = plateauStats.quantile(1.0 - PLATEAU_COVERAGE + PLATEAU_FADE / 2.0);
        NoiseStats hillsStats = new NoiseStats(hillsZone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.hillsLow = hillsStats.quantile(1.0 - HILLS_COVERAGE - HILLS_FADE / 2.0);
        this.hillsHigh = hillsStats.quantile(1.0 - HILLS_COVERAGE + HILLS_FADE / 2.0);
    }

    /**
     * Elevation added on top of the continent base.
     *
     * @param baseAboveSea continent base height above the sea surface in blocks
     */
    double height(double x, double z, double baseAboveSea) {
        double land = smoothstep(0.0, COAST_FADE_HEIGHT, baseAboveSea);
        if (land <= 0.0) {
            return 0.0;
        }
        double mountainZone = smoothstep(zoneLow, zoneHigh, zone.sample(x, z));
        double massif = scale * MASSIF_HEIGHT * mountainZone;
        // Warp the lookup so ridges between sites curve instead of running dead straight
        double wx = x + RIDGE_WARP * ridgeWarpX.sample(x, z);
        double wz = z + RIDGE_WARP * ridgeWarpZ.sample(x, z);
        double[] mountain = mountains(wx, wz);
        double peaks = mountain[0];
        double rugged = peaks > 0.0 ? TEXTURE_SHARE * peaks * (2.0 * orientedTexture(x, z) - 1.0) : 0.0;
        double foothill = scale * FOOTHILL_HEIGHT * mountain[1] * (0.5 + 0.5 * foothills.sample(x, z));

        double plateauWeight = smoothstep(plateauLow, plateauHigh, plateauZone.sample(x, z));
        double plateau = scale * plateauWeight * (PLATEAU_MIN + (PLATEAU_MAX - PLATEAU_MIN) * (0.5 + 0.5 * plateauHeight.sample(x, z)));

        double hillsWeight = smoothstep(hillsLow, hillsHigh, hillsZone.sample(x, z)) * (1.0 - mountainZone);
        double rolling = scale * HILLS_HEIGHT * hillsWeight * (0.5 + 0.5 * hills.sample(x, z));

        return land * (massif + peaks + rugged + foothill + plateau + rolling);
    }

    /** Tallest mountain profile at a position, and the strongest foothill belt weight, as {height, weight}. */
    private double[] mountains(double x, double z) {
        int cellX = (int) Math.floor(x / SITE_SPACING);
        int cellZ = (int) Math.floor(z / SITE_SPACING);
        double peak = 0.0;
        double belt = 0.0;
        for (int dx = -SITE_SEARCH; dx <= SITE_SEARCH; dx++) {
            for (int dz = -SITE_SEARCH; dz <= SITE_SEARCH; dz++) {
                Site site = site(cellX + dx, cellZ + dz);
                if (site.type == NONE) {
                    continue;
                }
                if (site.type == RANGE && site.links.length > 0) {
                    for (Site other : site.links) {
                        double[] profile = ridgeProfile(x, z, site, other);
                        peak = smoothMax(peak, profile[0]);
                        belt = Math.max(belt, profile[1]);
                    }
                } else {
                    double height = site.height * ridgeFactor(x, z);
                    double u = Math.hypot(x - site.x, z - site.z) / halfWidth(height);
                    peak = smoothMax(peak, profile(u) * height);
                    belt = Math.max(belt, foothillBelt(u) * site.height / (scale * PEAK_MAX));
                }
            }
        }
        return new double[]{peak, belt};
    }

    /** Height and foothill weight of the ridge between two linked sites at a position. */
    private double[] ridgeProfile(double x, double z, Site a, Site b) {
        double sx = b.x - a.x;
        double sz = b.z - a.z;
        double lengthSquared = sx * sx + sz * sz;
        double t = Math.max(0.0, Math.min(1.0, ((x - a.x) * sx + (z - a.z) * sz) / lengthSquared));
        double distance = Math.hypot(x - (a.x + t * sx), z - (a.z + t * sz));
        double crest = a.height + t * (b.height - a.height);
        double height = crest * ridgeFactor(x, z);
        double u = distance / halfWidth(height);
        return new double[]{profile(u) * height, foothillBelt(u) * crest / (scale * PEAK_MAX)};
    }

    private double ridgeFactor(double x, double z) {
        return 1.0 - RIDGE_VARIATION + RIDGE_VARIATION * ridge.sample(x, z);
    }

    /** Half-width that keeps {@link #profile}'s steepest grade (1.5 H / W) at the mountain grade. */
    private double halfWidth(double height) {
        return Math.max(1.0, 1.5 * height / mountainGrade);
    }

    /** Ridged texture in [0, 1], stretched along the local range orientation. */
    private double orientedTexture(double x, double z) {
        double angle = Math.PI * 1.5 * orientation.sample(x, z);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double along = x * cos + z * sin;
        double across = -x * sin + z * cos;
        return texture.sample(along / TEXTURE_ALONG, across / TEXTURE_ACROSS);
    }

    /**
     * S-shaped cross section: rounded crest, steepest at mid slope, flattening toward the foot.
     * Steepest grade is 1.5 H / W at u = 0.5.
     */
    private static double profile(double u) {
        return u >= 1.0 ? 0.0 : 1.0 - u * u * (3.0 - 2.0 * u);
    }

    /**
     * Polynomial smooth maximum: exactly max(a, b) when they differ by more than the blend width, rounded
     * within it. The blend width shrinks with the smaller value, so a profile fading to zero never lifts the
     * terrain around it.
     */
    private static double smoothMax(double a, double b) {
        double k = Math.min(MERGE_WIDTH, Math.min(a, b));
        if (k <= 0.0) {
            return Math.max(a, b);
        }
        double h = Math.max(0.0, Math.min(1.0, 0.5 + 0.5 * (a - b) / k));
        return b + (a - b) * h + k * h * (1.0 - h);
    }

    /** Foothill belt around a mountain, in units of the mountain's half-width. */
    private static double foothillBelt(double u) {
        return smoothstep(0.5, 0.9, u) * (1.0 - smoothstep(1.6, 2.4, u));
    }

    /** Mountain site of a grid cell, with its links resolved; cached per thread. */
    private Site site(int cellX, int cellZ) {
        Map<Long, Site> cache = sites.get();
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        Site site = cache.get(key);
        if (site == null) {
            if (cache.size() >= SITE_CACHE_LIMIT) {
                cache.clear();
            }
            site = basicSite(cellX, cellZ);
            if (site.type == RANGE) {
                site = withLinks(site, cellX, cellZ);
            }
            cache.put(key, site);
        }
        return site;
    }

    /** Position, type and height of a site, without links. */
    private Site basicSite(int cellX, int cellZ) {
        long hash = Hashing.hash(siteSeed, cellX, cellZ);
        double x = (cellX + 0.5 + SITE_JITTER * (Hashing.unit(hash) - 0.5)) * SITE_SPACING;
        double z = (cellZ + 0.5 + SITE_JITTER * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * SITE_SPACING;
        double zoneWeight = smoothstep(zoneLow, zoneHigh, zone.sample(x, z));
        if (zoneWeight < SITE_MIN_ZONE) {
            return new Site(x, z, NONE, 0.0, new Site[0]);
        }
        long typeHash = Hashing.derive(hash, 1);
        int type = Hashing.unit(typeHash) < settings.mountainRangeProbability() ? RANGE : PEAK;
        double strength = 0.4 + 0.6 * zoneWeight;
        double height = scale * strength * (PEAK_MIN + (PEAK_MAX - PEAK_MIN) * Hashing.unit(Hashing.derive(hash, 2)));
        return new Site(x, z, type, height, new Site[0]);
    }

    /**
     * Links a range site to the range neighbor best aligned with the orientation field ahead of it. Each site
     * links forward only, so ranges form chains rather than a web of triangles.
     */
    private Site withLinks(Site site, int cellX, int cellZ) {
        double angle = Math.PI * 1.5 * orientation.sample(site.x, site.z);
        double dirX = Math.cos(angle);
        double dirZ = Math.sin(angle);
        Site ahead = null;
        double bestAhead = LINK_MIN_ALIGNMENT;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Site other = basicSite(cellX + dx, cellZ + dz);
                if (other.type != RANGE) {
                    continue;
                }
                double ox = other.x - site.x;
                double oz = other.z - site.z;
                double alignment = (ox * dirX + oz * dirZ) / Math.hypot(ox, oz);
                if (alignment > bestAhead) {
                    bestAhead = alignment;
                    ahead = other;
                }
            }
        }
        Site[] links = ahead != null ? new Site[]{ahead} : new Site[0];
        return new Site(site.x, site.z, site.type, site.height, links);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    private record Site(double x, double z, int type, double height, Site[] links) {
    }
}
