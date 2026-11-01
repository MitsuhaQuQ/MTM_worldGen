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
 *   <li>One low-frequency field divides land into three tiers, low to high: plains, hills and mountains. Being
 *       continuous, it puts hills between plains and mountains by itself, and it also rises into hills alone.
 *       It is held down along coasts, so hills stand in for mountains there.</li>
 *   <li>Mountain sites sit on a jittered grid inside the mountain tier. Sites link to the neighbor best aligned with a low-frequency
 *       orientation field, so neighboring ranges run roughly parallel and chain into long ridges.</li>
 *   <li>Mountains stand on a broad massif uplift of their zone. Cross sections are S-shaped, with the half-width
 *       derived from the slope limit; overlapping ridges merge through a smooth maximum, and a ridged texture
 *       sampled through the same warp as the ridges adds spurs and gullies.</li>
 *   <li>Ranges blend between two styles along their length: steep igneous and gentle wind-worn.</li>
 *   <li>Foothills form a belt around every range and peak.</li>
 *   <li>Plateaus are raised table lands in their own zones. Isolated peaks stand where hills meet mountains, and
 *       rounded dome hills inside the hill tier.</li>
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
    private static final long HILLS_SALT = 0x48494C4CL;
    private static final long ISOLATED_SALT = 0x49534F4CL;
    private static final long STYLE_SALT = 0x5354594C45L;
    private static final long CLIFF_SALT = 0x434C494646L;
    /** Wavelength of the wander that keeps cliff bands from following contour lines. */
    private static final double CLIFF_WARP_WAVELENGTH = 320.0;
    /** Steps of level shift across the full range of the cliff patch noise. */
    private static final double CLIFF_LEVEL_SHIFT = 4.0;
    /** Spurs on a peak: their number, and how far the noise swings them around the summit, in radians. */
    private static final int PEAK_LOBES_MIN = 4;
    private static final int PEAK_LOBES_RANGE = 4;
    private static final double PEAK_BEND = 3.0;
    /** Style field values at which a range is fully eroded and fully igneous. */
    private static final double STYLE_RANGE = 0.3;
    /** Cross section used to relate a range's width at half height to its full width. */
    private static final double TYPICAL_SHARPNESS = 0.15;

    /**
     * Sites searched in each direction. A link spans up to about 2.8 cells (diagonal neighbor plus jitter), so
     * a point near one end of a ridge can be almost 3 cells from the site that owns it; a smaller window cuts
     * ridges off and leaves cliffs.
     */
    private static final int SITE_SEARCH = 3;






    private static final int STATS_SAMPLES_PER_AXIS = 96;
    private static final double STATS_SPACING = 1536.0;
    /** Slots of the per-thread site cache, a power of two. */
    private static final int SITE_CACHE_SIZE = 4096;
    private static final int SITE_CACHE_LIMIT = 4096;

    /** Position in the cross section where the summit weight reaches zero. */
    private static final double SUMMIT_FADE = 0.3;

    private static final int NONE = 0;
    private static final int RANGE = 1;

    private final long siteSeed;
    private final Fbm zone;
    private final Fbm orientation;
    private final Fbm ridge;
    private final Fbm ridgeWarpX;
    private final Fbm ridgeWarpZ;
    private final RidgedNoise texture;
    private final Fbm massifTexture;
    private final RidgedNoise gullies;
    private final Fbm foothills;
    private final Fbm plateauZone;
    private final Fbm plateauHeight;
    private final Fbm hills;
    private final Fbm style;
    private final Fbm cliffZone;
    private final Fbm cliffWarp;
    private final Fbm peakWarp;
    private final ContinentLayer continents;
    private final long isolatedSeed;
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
    private final double halfHeightU;
    private final double zoneLow;
    private final double zoneHigh;
    private final double plateauLow;
    private final double plateauHigh;
    private final double plainsLow;
    private final double plainsHigh;
    private final double hillsMiddle;
    private final ThreadLocal<SiteCache> sites = ThreadLocal.withInitial(SiteCache::new);
    private final ThreadLocal<Map<Long, Isolated>> isolated = ThreadLocal.withInitial(HashMap::new);

    SkeletonLayer(long seed, WorldgenSettings settings, ContinentLayer continents) {
        this.settings = settings;
        this.continents = continents;
        this.isolatedSeed = Hashing.derive(seed, ISOLATED_SALT);
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
        this.massifTexture = new Fbm(Hashing.derive(seed, DETAIL_SALT + 1), 3, m.massifTextureWavelength);
        this.gullies = new RidgedNoise(Hashing.derive(seed, DETAIL_SALT + 2), 3);
        this.foothills = new Fbm(Hashing.derive(seed, FOOTHILL_SALT), 3, m.foothillWavelength * scale);
        this.plateauZone = new Fbm(Hashing.derive(seed, PLATEAU_ZONE_SALT), 3, plateaus.zoneWavelength);
        this.plateauHeight = new Fbm(Hashing.derive(seed, PLATEAU_HEIGHT_SALT), 2, plateaus.zoneWavelength);
        this.hills = new Fbm(Hashing.derive(seed, HILLS_SALT), 3, hillSettings.wavelength * scale);
        this.style = new Fbm(Hashing.derive(seed, STYLE_SALT), 2, m.styleWavelength);
        this.cliffZone = new Fbm(Hashing.derive(seed, CLIFF_SALT), 2, m.cliffWavelength);
        this.cliffWarp = new Fbm(Hashing.derive(seed, CLIFF_SALT + 1), 2, CLIFF_WARP_WAVELENGTH);
        this.peakWarp = new Fbm(Hashing.derive(seed, ISOLATED_SALT + 1), 2, settings.isolated.peakWarpWavelength);
        this.mountainGrade = settings.slopes.mountain / 100.0 * m.profileSlopeShare;
        this.halfHeightU = findHalfHeightU();

        NoiseStats zoneStats = new NoiseStats(zone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.zoneLow = zoneStats.quantile(1.0 - m.zoneCoverage - m.zoneFade);
        this.zoneHigh = zoneStats.quantile(1.0 - m.zoneCoverage + m.zoneFade);
        this.plainsLow = zoneStats.quantile(settings.plains.coverage - settings.plains.fade / 2.0);
        this.plainsHigh = zoneStats.quantile(settings.plains.coverage + settings.plains.fade / 2.0);
        this.hillsMiddle = zoneStats.quantile(settings.plains.coverage + 0.5 * (1.0 - settings.plains.coverage - m.zoneCoverage));
        NoiseStats plateauStats = new NoiseStats(plateauZone::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING);
        this.plateauLow = plateauStats.quantile(1.0 - plateaus.coverage - plateaus.fade / 2.0);
        this.plateauHigh = plateauStats.quantile(1.0 - plateaus.coverage + plateaus.fade / 2.0);
    }

    /**
     * Elevation added on top of the continent base, without the ridge texture.
     *
     * @param baseAboveSea continent base height above the sea surface in blocks
     * @param texture      receives the ridge texture in its first element, or null if it is not needed
     */
    double height(double x, double z, double baseAboveSea, double[] texture) {
        double land = smoothstep(0.0, settings.general.coastFadeHeight, baseAboveSea);
        if (land <= 0.0) {
            if (texture != null) {
                texture[0] = 0.0;
            }
            return 0.0;
        }
        double tier = tier(x, z, baseAboveSea);
        double mountainZone = smoothstep(zoneLow, zoneHigh, tier);
        double plain = 1.0 - smoothstep(plainsLow, plainsHigh, tier);
        double hilly = (1.0 - plain) * (1.0 - mountainZone);
        double openLand = 1.0 - plain * (1.0 - settings.plains.reliefShare);
        double massif = scale * m.massifHeight * mountainZone;
        // Warp the lookup so ridges between sites curve instead of running dead straight
        double wx = x + m.ridgeWarp * ridgeWarpX.sample(x, z);
        double wz = z + m.ridgeWarp * ridgeWarpZ.sample(x, z);
        double[] mountain = mountains(wx, wz, texture != null);
        double peaks = mountain[0];
        double textured = peaks + m.massifTextureShare * massif;
        double summitCalm = 1.0 - m.summitTextureReduction * mountain[2];
        double rugged = 0.0;
        if (texture != null && textured > 0.0) {
            // Spurs near ridges fade into crease-free noise on the open massif, where they have no direction to follow
            double openWeight = scale * m.massifHeight;
            openWeight *= openWeight;
            double open = 0.5 + 0.5 * massifTexture.sample(wx, wz);
            double relief = (mountain[3] + openWeight * open) / (mountain[4] + openWeight);
            rugged = mix(m.erodedTexture, m.igneousTexture, style(wx, wz)) * summitCalm * m.textureShare * textured * (2.0 * relief - 1.0);
        }
        if (texture != null) {
            double cliffs = cliffMask(x, z, mountainZone);
            if (cliffs > 0.0) {
                rugged += cliffs * summitCalm * cliffOffset(x, z, massif + peaks + rugged);
            }
        }
        double foothill = scale * m.foothillHeight * mountain[1] * (0.5 + 0.5 * foothills.sample(x, z));

        double plateauWeight = smoothstep(plateauLow, plateauHigh, plateauZone.sample(x, z));
        double plateau = openLand * scale * plateauWeight * (plateaus.minHeight + (plateaus.maxHeight - plateaus.minHeight) * (0.5 + 0.5 * plateauHeight.sample(x, z)));

        double rolling = scale * hillSettings.height * (hilly + settings.plains.reliefShare * plain) * (0.5 + 0.5 * hills.sample(x, z));

        if (texture != null) {
            texture[0] = land * rugged;
        }
        return land * (massif + peaks + foothill + plateau + rolling + isolatedHeight(x, z));
    }

    /** Whether a position lies in the hill or mountain tier, where rivers can start. */
    boolean isUpland(double x, double z, double baseAboveSea) {
        return tier(x, z, baseAboveSea) >= plainsHigh;
    }

    /**
     * The tier field at a position: low values are plains, middle ones hills, high ones mountains. Near the coast it
     * is held down to the middle of the hills, which turns mountains into hills and leaves plains as they are.
     */
    private double tier(double x, double z, double baseAboveSea) {
        double value = zone.sample(x, z);
        if (value <= hillsMiddle) {
            return value;
        }
        double inland = smoothstep(0.0, m.coastBand, continents.inlandDistance(baseAboveSea));
        return hillsMiddle + inland * (value - hillsMiddle);
    }

    /** Height of the isolated peaks and dome hills around a position. */
    private double isolatedHeight(double x, double z) {
        WorldgenSettings.Isolated config = settings.isolated;
        int cellX = (int) Math.floor(x / config.spacing);
        int cellZ = (int) Math.floor(z / config.spacing);
        double total = 0.0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Isolated hill = isolated(cellX + dx, cellZ + dz);
                if (hill.height > 0.0) {
                    double u = Math.hypot(x - hill.x, z - hill.z) / (hill.peak ? hill.radius * peakOutline(hill, x, z) : hill.radius);
                    if (u < 1.0) {
                        total += hill.height * (hill.peak ? (1.0 - u) * (1.0 - u) : 0.5 + 0.5 * Math.cos(Math.PI * u));
                    }
                }
            }
        }
        return total;
    }

    /**
     * How far a peak reaches toward a position, as a share of its radius. Lobes around the summit run straight down the
     * slopes as spurs, and the noise bends them so that no two peaks are the same cone.
     */
    private double peakOutline(Isolated hill, double x, double z) {
        WorldgenSettings.Isolated config = settings.isolated;
        double wander = peakWarp.sample(x, z);
        double spoke = Math.sin(hill.lobes * Math.atan2(z - hill.z, x - hill.x) + hill.phase + PEAK_BEND * wander);
        return 1.0 + config.peakSpur * spoke + config.peakWarp * wander;
    }

    private Isolated isolated(int cellX, int cellZ) {
        Map<Long, Isolated> cache = isolated.get();
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        Isolated hill = cache.get(key);
        if (hill == null) {
            if (cache.size() >= SITE_CACHE_LIMIT) {
                cache.clear();
            }
            hill = createIsolated(cellX, cellZ);
            cache.put(key, hill);
        }
        return hill;
    }

    private Isolated createIsolated(int cellX, int cellZ) {
        WorldgenSettings.Isolated config = settings.isolated;
        long hash = Hashing.hash(isolatedSeed, cellX, cellZ);
        double x = (cellX + 0.5 + 0.7 * (Hashing.unit(hash) - 0.5)) * config.spacing;
        double z = (cellZ + 0.5 + 0.7 * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * config.spacing;
        double roll = Hashing.unit(Hashing.derive(hash, 2));
        double size = Hashing.unit(Hashing.derive(hash, 3));
        double base = continents.height(continents.value(x, z)) - (settings.general.seaLevel - 1);
        if (base <= settings.general.coastFadeHeight) {
            return new Isolated(x, z, 0.0, 1.0, false, 0, 0.0);
        }
        double tier = tier(x, z, base);
        if (tier >= hillsMiddle && tier < zoneHigh) {
            if (roll < config.peakChance) {
                double height = scale * (config.peakMin + size * (config.peakMax - config.peakMin));
                return new Isolated(x, z, height, config.peakSpread * height, true, PEAK_LOBES_MIN + (int) (Hashing.unit(Hashing.derive(hash, 4)) * PEAK_LOBES_RANGE),
                        2.0 * Math.PI * Hashing.unit(Hashing.derive(hash, 5)));
            }
        } else if (tier >= plainsHigh && tier < hillsMiddle && roll < config.domeChance) {
            double height = scale * (config.domeMin + size * (config.domeMax - config.domeMin));
            return new Isolated(x, z, height, config.domeSpread * height, false, 0, 0.0);
        }
        return new Isolated(x, z, 0.0, 1.0, false, 0, 0.0);
    }

    /**
     * Tallest mountain profile at a position with the strongest foothill and summit weights, plus the spur texture
     * summed over nearby ridges weighted by squared height and distance, as {height, belt, summit, spurSum, spurWeight}.
     */
    private double[] mountains(double x, double z, boolean textured) {
        int cellX = (int) Math.floor(x / m.siteSpacing);
        int cellZ = (int) Math.floor(z / m.siteSpacing);
        double peak = 0.0;
        double belt = 0.0;
        double summit = 0.0;
        double spurSum = 0.0;
        double spurWeight = 0.0;
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
                    summit = Math.max(summit, profile[2]);
                    if (textured && profile[3] < m.textureReach) {
                        double weight = profile[4] * (1.0 - smoothstep(0.0, m.textureReach, profile[3]));
                        weight *= weight;
                        spurSum += weight * spurTexture(x, z, site, other);
                        spurWeight += weight;
                    }
                }
            }
        }
        return new double[]{peak, belt, summit, spurSum, spurWeight};
    }

    /**
     * Ridged texture in the frame of a ridge, stretched across it. Plain ridged noise creases along closed loops
     * that read as rings on a slope; stretched like this the creases run down the flanks as spurs and gullies.
     */
    private double spurTexture(double x, double z, Site a, Site b) {
        double sx = b.x - a.x;
        double sz = b.z - a.z;
        double length = Math.hypot(sx, sz);
        // The bend keeps spurs from running ruler straight; it adds no rotation, so it cannot wind them into spirals
        double along = ((x - a.x) * sx + (z - a.z) * sz) / length + m.textureBend * massifTexture.sample(x, z);
        double across = ((x - a.x) * sz - (z - a.z) * sx) / length;
        return texture.sample(along / m.textureWavelength, across / (m.textureWavelength * m.textureStretch))
                + m.gullyShare * (gullies.sample(along / m.gullyWavelength, across / (m.gullyWavelength * m.textureStretch)) - 0.5);
    }

    /** Height, foothill weight, summit weight, distance and crest height of the ridge between two linked sites at a position. */
    private double[] ridgeProfile(double x, double z, Site a, Site b) {
        double sx = b.x - a.x;
        double sz = b.z - a.z;
        double lengthSquared = sx * sx + sz * sz;
        double t = Math.max(0.0, Math.min(1.0, ((x - a.x) * sx + (z - a.z) * sz) / lengthSquared));
        double distance = Math.hypot(x - (a.x + t * sx), z - (a.z + t * sz));
        double crest = a.height + t * (b.height - a.height);
        double height = crest * ridgeFactor(x, z);
        // The profile starts at the edge of the summit band, leaving the band itself at crest height
        double slopeDistance = Math.max(0.0, distance - 0.5 * m.summitWidth);
        double style = a.style + t * (b.style - a.style);
        double sharpness = mix(m.erodedSharpness, m.igneousSharpness, style);
        double u = slopeDistance / halfWidth(height, a.width + t * (b.width - a.width), sharpness, style);
        double summit = 1.0 - smoothstep(0.0, SUMMIT_FADE, u);
        return new double[]{profile(u, sharpness) * height, foothillBelt(u) * crest / (scale * m.peakMax), summit, distance, height};
    }

    private double ridgeFactor(double x, double z) {
        return 1.0 - m.ridgeVariation + m.ridgeVariation * ridge.sample(x, z);
    }

    /**
     * Base half-width of a mountain whose width at half height is the given value, widened if needed so that
     * {@link #profile}'s steepest grade, at most (1.5 + 0.5 sharpness) H / W, stays at the mountain grade.
     */
    private double halfWidth(double height, double width, double sharpness, double style) {
        double fromWidth = 0.5 * (width - m.summitWidth) / halfHeightU;
        double fromSlope = (1.5 + 0.5 * sharpness) * height / (mountainGrade * mix(m.erodedGrade, 1.0, style));
        return Math.max(fromWidth, fromSlope);
    }

    /** Position u in the cross section where {@link #profile} falls to one half. */
    private double findHalfHeightU() {
        double low = 0.0;
        double high = 1.0;
        for (int i = 0; i < 32; i++) {
            double mid = 0.5 * (low + high);
            if (profile(mid, TYPICAL_SHARPNESS) > 0.5) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Cross section mixing an S curve (rounded crest, steepest at mid slope, grade 1.5 H / W) with a concave curve
     * (sharp crest, steepest at the top, grade 2 H / W). Both flatten toward the foot.
     */
    private double profile(double u, double sharpness) {
        if (u >= 1.0) {
            return 0.0;
        }
        double rounded = 1.0 - u * u * (3.0 - 2.0 * u);
        double sharp = (1.0 - u) * (1.0 - u);
        return rounded + sharpness * (sharp - rounded);
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
        SiteCache cache = sites.get();
        long key = ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
        int slot = (cellX * 31 + cellZ) & (SITE_CACHE_SIZE - 1);
        Site site = cache.sites[slot];
        if (site == null || cache.keys[slot] != key) {
            site = basicSite(cellX, cellZ);
            if (site.type == RANGE) {
                site = withLinks(site, cellX, cellZ);
            }
            cache.sites[slot] = site;
            cache.keys[slot] = key;
        }
        return site;
    }

    /** Position, type and height of a site, without links. */
    private Site basicSite(int cellX, int cellZ) {
        long hash = Hashing.hash(siteSeed, cellX, cellZ);
        double x = (cellX + 0.5 + m.siteJitter * (Hashing.unit(hash) - 0.5)) * m.siteSpacing;
        double z = (cellZ + 0.5 + m.siteJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * m.siteSpacing;
        double base = continents.height(continents.value(x, z)) - (settings.general.seaLevel - 1);
        double zoneWeight = smoothstep(zoneLow, zoneHigh, tier(x, z, base));
        if (zoneWeight < m.siteMinZone) {
            return new Site(x, z, NONE, 0.0, 0.0, 0.0, new Site[0]);
        }
        double strength = 0.4 + 0.6 * zoneWeight;
        double height = scale * strength * (m.peakMin + (m.peakMax - m.peakMin) * Hashing.unit(Hashing.derive(hash, 2)));
        double width = m.widthMin + (m.widthMax - m.widthMin) * Hashing.unit(Hashing.derive(hash, 3));
        double style = style(x, z);
        height *= mix(m.erodedHeight, m.igneousHeight, style);
        width *= mix(m.erodedWidth, m.igneousWidth, style);
        return new Site(x, z, RANGE, height, width, style, new Site[0]);
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
        return new Site(site.x, site.z, site.type, site.height, site.width, site.style, links);
    }

    /** Share of cliff bands at a position: they form patches on steep igneous ranges. */
    private double cliffMask(double x, double z, double mountainZone) {
        double igneous = mountainZone > 0.0 ? style(x, z) : 0.0;
        // Cliffs are all or nothing: half a cliff is only a slightly uneven slope
        double strength = igneous * mountainZone * smoothstep(m.cliffLow, m.cliffHigh, cliffZone.sample(x, z));
        return smoothstep(0.1, 0.5, strength);
    }

    /** Share of cliff bands at a position with the given continent base height above the sea. */
    double cliffShare(double x, double z, double baseAboveSea) {
        return cliffMask(x, z, smoothstep(zoneLow, zoneHigh, tier(x, z, baseAboveSea)));
    }

    /** Height change that gathers a slope into steps: nearly flat ledges with a steep rise between them. */
    private double cliffOffset(double x, double z, double height) {
        double step = scale * m.cliffStep;
        // The level shifts from patch to patch, so that cliffs on one mountain do not line up into rings around it
        double value = (height + 0.5 * step * cliffWarp.sample(x, z)) / step + CLIFF_LEVEL_SHIFT * cliffZone.sample(x, z);
        double t = value - Math.floor(value);
        double stepped = t * (1.0 - m.cliffStrength) + m.cliffStrength * smoothstep(0.5 - m.cliffWidth, 0.5 + m.cliffWidth, t);
        return (stepped - t) * step;
    }

    /** Whether a position leans to the igneous style. */
    boolean isIgneous(double x, double z) {
        return style(x, z) >= 0.5;
    }

    /** How igneous a position is: 0 for smooth wind-worn ranges, 1 for steep igneous ones, blending between them. */
    private double style(double x, double z) {
        return smoothstep(-STYLE_RANGE, STYLE_RANGE, style.sample(x, z));
    }

    private static double mix(double from, double to, double t) {
        return from + t * (to - from);
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0 - 2.0 * t);
    }

    /** Direct-mapped cache: a lookup costs one array read, and a miss only replaces the site in its slot. */
    private static final class SiteCache {
        final Site[] sites = new Site[SITE_CACHE_SIZE];
        final long[] keys = new long[SITE_CACHE_SIZE];
    }

    private record Isolated(double x, double z, double height, double radius, boolean peak, int lobes, double phase) {
    }

    private record Site(double x, double z, int type, double height, double width, double style, Site[] links) {
    }
}
