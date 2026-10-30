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
 *   <li>Mountain sites sit on a jittered grid inside low-frequency orogeny zones. Sites link to the neighbor best aligned with a low-frequency
 *       orientation field, so neighboring ranges run roughly parallel and chain into long ridges.</li>
 *   <li>Mountains stand on a broad massif uplift of their zone. Cross sections are S-shaped, with the half-width
 *       derived from the slope limit; overlapping ridges merge through a smooth maximum, and a ridged texture
 *       sampled through the same warp as the ridges adds spurs and gullies.</li>
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

    /**
     * Sites searched in each direction. A link spans up to about 2.8 cells (diagonal neighbor plus jitter), so
     * a point near one end of a ridge can be almost 3 cells from the site that owns it; a smaller window cuts
     * ridges off and leaves cliffs.
     */
    private static final int SITE_SEARCH = 3;






    private static final int STATS_SAMPLES_PER_AXIS = 96;
    private static final double STATS_SPACING = 1536.0;
    private static final int SITE_CACHE_LIMIT = 4096;

    private static final int NONE = 0;
    private static final int RANGE = 1;

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
    private final WorldgenSettings.Mountains m;
    private final WorldgenSettings.Plateaus plateaus;
    private final WorldgenSettings.Hills hillSettings;
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
        this.m = settings.mountains;
        this.plateaus = settings.plateaus;
        this.hillSettings = settings.hills;
        this.scale = settings.heightScale();
        this.siteSeed = Hashing.derive(seed, SITE_SALT);
        this.zone = new Fbm(Hashing.derive(seed, ZONE_SALT), 3, m.zoneWavelength);
        this.orientation = new Fbm(Hashing.derive(seed, ORIENTATION_SALT), 2, m.orientationWavelength);
        this.ridge = new Fbm(Hashing.derive(seed, RIDGE_SALT), 2, m.ridgeWavelength);
        this.ridgeWarpX = new Fbm(Hashing.derive(seed, RIDGE_SALT + 1), 2, m.ridgeWarpWavelength);
        this.ridgeWarpZ = new Fbm(Hashing.derive(seed, RIDGE_SALT + 2), 2, m.ridgeWarpWavelength);
        this.texture = new RidgedNoise(Hashing.derive(seed, DETAIL_SALT), 4);
        this.foothills = new Fbm(Hashing.derive(seed, FOOTHILL_SALT), 3, m.foothillWavelength * scale);
        this.plateauZone = new Fbm(Hashing.derive(seed, PLATEAU_ZONE_SALT), 3, plateaus.zoneWavelength);
        this.plateauHeight = new Fbm(Hashing.derive(seed, PLATEAU_HEIGHT_SALT), 2, plateaus.zoneWavelength);
        this.hillsZone = new Fbm(Hashing.derive(seed, HILLS_ZONE_SALT), 3, hillSettings.zoneWavelength);
        this.hills = new Fbm(Hashing.derive(seed, HILLS_SALT), 3, hillSettings.wavelength * scale);
        this.mountainGrade = settings.slopes.mountain / 100.0 * m.profileSlopeShare;

        NoiseStats zoneStats = new NoiseStats(zone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.zoneLow = zoneStats.quantile(1.0 - m.zoneCoverage - m.zoneFade);
        this.zoneHigh = zoneStats.quantile(1.0 - m.zoneCoverage + m.zoneFade);
        NoiseStats plateauStats = new NoiseStats(plateauZone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.plateauLow = plateauStats.quantile(1.0 - plateaus.coverage - plateaus.fade / 2.0);
        this.plateauHigh = plateauStats.quantile(1.0 - plateaus.coverage + plateaus.fade / 2.0);
        NoiseStats hillsStats = new NoiseStats(hillsZone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.hillsLow = hillsStats.quantile(1.0 - hillSettings.coverage - hillSettings.fade / 2.0);
        this.hillsHigh = hillsStats.quantile(1.0 - hillSettings.coverage + hillSettings.fade / 2.0);
    }

    /**
     * Elevation added on top of the continent base.
     *
     * @param baseAboveSea continent base height above the sea surface in blocks
     */
    double height(double x, double z, double baseAboveSea) {
        double land = smoothstep(0.0, settings.general.coastFadeHeight, baseAboveSea);
        if (land <= 0.0) {
            return 0.0;
        }
        double mountainZone = smoothstep(zoneLow, zoneHigh, zone.sample(x, z));
        double massif = scale * m.massifHeight * mountainZone;
        // Warp the lookup so ridges between sites curve instead of running dead straight
        double wx = x + m.ridgeWarp * ridgeWarpX.sample(x, z);
        double wz = z + m.ridgeWarp * ridgeWarpZ.sample(x, z);
        double[] mountain = mountains(wx, wz);
        double peaks = mountain[0];
        double textured = peaks + m.massifTextureShare * massif;
        double rugged = textured > 0.0 ? m.textureShare * textured * (2.0 * texture.sample(wx / m.textureWavelength, wz / m.textureWavelength) - 1.0) : 0.0;
        double foothill = scale * m.foothillHeight * mountain[1] * (0.5 + 0.5 * foothills.sample(x, z));

        double plateauWeight = smoothstep(plateauLow, plateauHigh, plateauZone.sample(x, z));
        double plateau = scale * plateauWeight * (plateaus.minHeight + (plateaus.maxHeight - plateaus.minHeight) * (0.5 + 0.5 * plateauHeight.sample(x, z)));

        double hillsWeight = smoothstep(hillsLow, hillsHigh, hillsZone.sample(x, z)) * (1.0 - mountainZone);
        double rolling = scale * hillSettings.height * hillsWeight * (0.5 + 0.5 * hills.sample(x, z));

        return land * (massif + peaks + rugged + foothill + plateau + rolling);
    }

    /** Tallest mountain profile at a position, and the strongest foothill belt weight, as {height, weight}. */
    private double[] mountains(double x, double z) {
        int cellX = (int) Math.floor(x / m.siteSpacing);
        int cellZ = (int) Math.floor(z / m.siteSpacing);
        double peak = 0.0;
        double belt = 0.0;
        for (int dx = -SITE_SEARCH; dx <= SITE_SEARCH; dx++) {
            for (int dz = -SITE_SEARCH; dz <= SITE_SEARCH; dz++) {
                Site site = site(cellX + dx, cellZ + dz);
                if (site.type == NONE) {
                    continue;
                }
                for (Site other : site.links) {
                    double[] profile = ridgeProfile(x, z, site, other);
                    peak = smoothMax(peak, profile[0]);
                    belt = Math.max(belt, profile[1]);
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
        return new double[]{profile(u) * height, foothillBelt(u) * crest / (scale * m.peakMax)};
    }

    private double ridgeFactor(double x, double z) {
        return 1.0 - m.ridgeVariation + m.ridgeVariation * ridge.sample(x, z);
    }

    /** Half-width that keeps {@link #profile}'s steepest grade, at most (1.5 + 0.5 sharpness) H / W, at the mountain grade. */
    private double halfWidth(double height) {
        return Math.max(1.0, (1.5 + 0.5 * m.crestSharpness) * height / mountainGrade);
    }

    /**
     * Cross section mixing an S curve (rounded crest, steepest at mid slope, grade 1.5 H / W) with a concave curve
     * (sharp crest, steepest at the top, grade 2 H / W). Both flatten toward the foot.
     */
    private double profile(double u) {
        if (u >= 1.0) {
            return 0.0;
        }
        double rounded = 1.0 - u * u * (3.0 - 2.0 * u);
        double sharp = (1.0 - u) * (1.0 - u);
        return rounded + m.crestSharpness * (sharp - rounded);
    }

    /**
     * Polynomial smooth maximum: exactly max(a, b) when they differ by more than the blend width, rounded
     * within it. The blend width shrinks with the smaller value, so a profile fading to zero never lifts the
     * terrain around it.
     */
    private double smoothMax(double a, double b) {
        double k = Math.min(m.mergeWidth, Math.min(a, b));
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
        double x = (cellX + 0.5 + m.siteJitter * (Hashing.unit(hash) - 0.5)) * m.siteSpacing;
        double z = (cellZ + 0.5 + m.siteJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * m.siteSpacing;
        double zoneWeight = smoothstep(zoneLow, zoneHigh, zone.sample(x, z));
        if (zoneWeight < m.siteMinZone) {
            return new Site(x, z, NONE, 0.0, new Site[0]);
        }
        double strength = 0.4 + 0.6 * zoneWeight;
        double height = scale * strength * (m.peakMin + (m.peakMax - m.peakMin) * Hashing.unit(Hashing.derive(hash, 2)));
        return new Site(x, z, RANGE, height, new Site[0]);
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
        double bestAhead = m.linkMinAlignment;
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
