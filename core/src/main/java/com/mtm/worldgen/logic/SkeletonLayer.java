package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;
import com.mtm.worldgen.noise.RidgedNoise;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;

/**
 * Pipeline step 3: terrain skeleton on land, added on top of the continent base. A pure function of
 * seed + block coordinates, evaluated per block column so ridges and peaks stay sharp.
 *
 * <ul>
 *   <li>One low-frequency field divides land into three tiers, low to high: plains, hills and mountains. Being
 *       continuous, it puts hills between plains and mountains by itself, and it also rises into hills alone.
 *       It is held down along coasts, so hills stand in for mountains there. It rises into mountain belts along the
 *       borders of plates that move together, and sinks toward plains where they move apart.</li>
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
    private static final long GREAT_PLAINS_SALT = 0x475245415450L;
    private static final long SITE_SALT = 0x53495445L;
    private static final long ORIENTATION_SALT = 0x4F5249454E54L;
    private static final long RIDGE_SALT = 0x5249444745L;
    private static final long DETAIL_SALT = 0x524F434B59L;
    private static final long FOOTHILL_SALT = 0x464F4F54L;
    private static final long PLATEAU_ZONE_SALT = 0x504C4154L;
    private static final long PLATEAU_HEIGHT_SALT = 0x504C4854L;
    /** Range of the edge noise over which a plateau edge turns from gentle to steep. */
    private static final double EDGE_BLEND = 0.08;
    /** How much wider than usual the statistics grid is for fields as slow as the great plains. */
    private static final int GREAT_STATS_SPREAD = 3;
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
    /** Convergence at which a mountain belt steers its ranges fully along the plate border. */
    private static final double BELT_FULL = 0.3;
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
    private final Fbm plateauEdge;
    private final Fbm hills;
    private final Fbm style;
    private final Fbm cliffZone;
    private final Fbm cliffWarp;
    private final Fbm peakWarp;
    private final ContinentLayer continents;
    /** How much a position belongs to the desert, before the coast and the land's shape have their say. */
    private final DoubleBinaryOperator desert;
    private final long isolatedSeed;
    private final WorldgenSettings settings;
    private final WorldgenSettings.Mountains m;
    private final WorldgenSettings.Plateaus plateaus;
    private final WorldgenSettings.Hills hillSettings;
    /**
     * Heights above (and the hill wavelengths that keep their slopes) are designed for a 300 block maximum
     * surface and scaled by designHeight / 300, as the design document specifies.
     */
    private final double scale;
    private final double mountainGrade;
    /**
     * How much taller than designed ranges on a plateau grow, as a share of their height: enough for the tallest range
     * to reach from the design height up to the surface maximum. None where the two are the same.
     */
    private final double plateauPeakGain;
    private static final double PLATEAU_PEAK_REACH = 0.8;
    private final double halfHeightU;
    private final double zoneLow;
    private final double zoneHigh;
    private final double plateauLow;
    private final double plateauHigh;
    private final double gentleLow;
    private final double gentleHigh;
    private final double steepEdge;
    private final double plainsLow;
    private final Fbm greatPlains;
    private final double greatLow;
    private final double greatHigh;
    private final double siteSpacing;
    /** Half-width of mountain belts along plate borders, and how far the plateaus beside them reach, in blocks. */
    private final double beltWidth;
    private final double plateauReach;
    private final double isolatedSpacing;
    private final double plainsHigh;
    private final double hillsMiddle;
    private final ThreadLocal<SiteCache> sites = ThreadLocal.withInitial(SiteCache::new);
    private final ThreadLocal<Map<Long, Isolated>> isolated = ThreadLocal.withInitial(HashMap::new);

    SkeletonLayer(long seed, WorldgenSettings settings, ContinentLayer continents, DoubleBinaryOperator desert) {
        this.settings = settings;
        this.continents = continents;
        this.desert = desert;
        this.isolatedSeed = Hashing.derive(seed, ISOLATED_SALT);
        this.m = settings.mountains;
        this.plateaus = settings.plateaus;
        this.hillSettings = settings.hills;
        this.scale = settings.heightScale();
        this.siteSeed = Hashing.derive(seed, SITE_SALT);
        WorldgenSettings.Scale size = settings.scale;
        this.siteSpacing = m.siteSpacing * size.mountains;
        this.beltWidth = m.beltWidth * size.terrainZones;
        this.plateauReach = settings.plateaus.beltReach * size.plateaus;
        this.isolatedSpacing = settings.isolated.spacing * size.isolated;
        this.zone = new Fbm(Hashing.derive(seed, ZONE_SALT), 3, m.zoneWavelength * size.terrainZones);
        this.orientation = new Fbm(Hashing.derive(seed, ORIENTATION_SALT), 2, m.orientationWavelength * size.mountains);
        this.ridge = new Fbm(Hashing.derive(seed, RIDGE_SALT), 2, m.ridgeWavelength);
        this.ridgeWarpX = new Fbm(Hashing.derive(seed, RIDGE_SALT + 1), 2, m.ridgeWarpWavelength * size.mountains);
        this.ridgeWarpZ = new Fbm(Hashing.derive(seed, RIDGE_SALT + 2), 2, m.ridgeWarpWavelength * size.mountains);
        this.texture = new RidgedNoise(Hashing.derive(seed, DETAIL_SALT), 4);
        this.massifTexture = new Fbm(Hashing.derive(seed, DETAIL_SALT + 1), 3, m.massifTextureWavelength);
        this.gullies = new RidgedNoise(Hashing.derive(seed, DETAIL_SALT + 2), 3);
        this.foothills = new Fbm(Hashing.derive(seed, FOOTHILL_SALT), 3, m.foothillWavelength * scale);
        this.plateauZone = new Fbm(Hashing.derive(seed, PLATEAU_ZONE_SALT), 3, plateaus.zoneWavelength * size.plateaus);
        this.plateauHeight = new Fbm(Hashing.derive(seed, PLATEAU_HEIGHT_SALT), 2, plateaus.zoneWavelength * size.plateaus);
        this.plateauEdge = new Fbm(Hashing.derive(seed, PLATEAU_HEIGHT_SALT + 1), 2, plateaus.edgeWavelength * size.plateaus);
        this.hills = new Fbm(Hashing.derive(seed, HILLS_SALT), 3, hillSettings.wavelength * scale * size.hills);
        this.style = new Fbm(Hashing.derive(seed, STYLE_SALT), 2, m.styleWavelength * size.mountains);
        this.cliffZone = new Fbm(Hashing.derive(seed, CLIFF_SALT), 2, m.cliffWavelength);
        this.cliffWarp = new Fbm(Hashing.derive(seed, CLIFF_SALT + 1), 2, CLIFF_WARP_WAVELENGTH);
        this.peakWarp = new Fbm(Hashing.derive(seed, ISOLATED_SALT + 1), 2, settings.isolated.peakWarpWavelength);
        this.mountainGrade = settings.slopes.mountain / 100.0 * m.profileSlopeShare;
        // Full strength and a whole plateau beneath come together only rarely, so the gain counts on a range reaching
        // PLATEAU_PEAK_REACH of the tallest design height
        this.plateauPeakGain = (settings.general.maxSurfaceHeight - settings.general.designHeight) / (PLATEAU_PEAK_REACH * scale * m.peakMax * m.igneousHeight);
        this.halfHeightU = findHalfHeightU();

        WorldgenSettings.Plains plains = settings.plains;
        this.greatPlains = new Fbm(Hashing.derive(seed, GREAT_PLAINS_SALT), 2, plains.greatWavelength * size.terrainZones);
        // The field is slow, so its statistics need a wider grid to see enough of it
        NoiseStats greatStats = new NoiseStats(greatPlains::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING * GREAT_STATS_SPREAD);
        this.greatLow = greatStats.quantile(1.0 - plains.greatCoverage - plains.greatFade / 2.0);
        this.greatHigh = greatStats.quantile(1.0 - plains.greatCoverage + plains.greatFade / 2.0);
        // Thresholds come from the zones as the great plains leave them, which keeps the share of each tier
        NoiseStats zoneStats = new NoiseStats(this::zoneValue, STATS_SAMPLES_PER_AXIS, STATS_SPACING * GREAT_STATS_SPREAD);
        this.zoneLow = zoneStats.quantile(1.0 - m.zoneCoverage - m.zoneFade);
        this.zoneHigh = zoneStats.quantile(1.0 - m.zoneCoverage + m.zoneFade);
        this.plainsLow = zoneStats.quantile(settings.plains.coverage - settings.plains.fade / 2.0);
        this.plainsHigh = zoneStats.quantile(settings.plains.coverage + settings.plains.fade / 2.0);
        this.hillsMiddle = zoneStats.quantile(settings.plains.coverage + 0.5 * (1.0 - settings.plains.coverage - m.zoneCoverage));
        // Plate highlands and belts are slow too; the plateau noise alone, as it was before plates, needs no wider grid
        NoiseStats plateauStats = new NoiseStats(this::plateauValue, STATS_SAMPLES_PER_AXIS, m.plateTectonics ? STATS_SPACING * GREAT_STATS_SPREAD : STATS_SPACING);
        this.plateauLow = plateauStats.quantile(1.0 - plateaus.coverage - plateaus.fade / 2.0);
        this.plateauHigh = plateauStats.quantile(1.0 - plateaus.coverage + plateaus.fade / 2.0);
        this.gentleLow = plateauStats.quantile(1.0 - plateaus.coverage - plateaus.gentleFade / 2.0);
        this.gentleHigh = plateauStats.quantile(1.0 - plateaus.coverage + plateaus.gentleFade / 2.0);
        this.steepEdge = new NoiseStats(plateauEdge::sample, STATS_SAMPLES_PER_AXIS, STATS_SPACING).quantile(1.0 - plateaus.steepShare);
    }

    /**
     * Elevation added on top of the continent base, without the ridge texture.
     *
     * @param baseAboveSea continent base height above the sea surface in blocks
     * @param texture      receives the ridge texture in its first element, or null if it is not needed
     * @param ridged       whether the texture holds the ridged spurs and gullies, or only the cliffs
     * @param carved       height erosion has taken off here, which cliffs are stepped from along with the land
     * @param cliffKeep    share of cliff bands kept here, from 0 to 1
     */
    double height(double x, double z, double baseAboveSea, double[] texture, boolean ridged, double carved, double cliffKeep) {
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
        double massif = scale * m.massifHeight * mountainZone;
        // Warp the lookup so ridges between sites curve instead of running dead straight
        double wx = x + m.ridgeWarp * ridgeWarpX.sample(x, z);
        double wz = z + m.ridgeWarp * ridgeWarpZ.sample(x, z);
        double[] mountain = mountains(wx, wz, texture != null && ridged);
        double peaks = mountain[0];
        double textured = peaks + m.massifTextureShare * massif;
        double summitCalm = 1.0 - m.summitTextureReduction * mountain[2];
        double rugged = 0.0;
        if (texture != null && ridged && textured > 0.0) {
            // Spurs near ridges fade into crease-free noise on the open massif, where they have no direction to follow
            double openWeight = scale * m.massifHeight;
            openWeight *= openWeight;
            double open = 0.5 + 0.5 * massifTexture.sample(wx, wz);
            double relief = (mountain[3] + openWeight * open) / (mountain[4] + openWeight);
            rugged = mix(m.erodedTexture, m.igneousTexture, style(wx, wz)) * summitCalm * m.textureShare * textured * (2.0 * relief - 1.0);
        }
        if (texture != null) {
            double cliffs = cliffKeep * cliffMask(x, z, mountainZone);
            if (cliffs > 0.0) {
                // Stepped from the land as erosion left it: from the land before, a ledge would flatten a slope that
                // erosion had already made gentler, and tilt back against the cliff above it, with any creek on it
                // running uphill
                rugged += cliffs * summitCalm * cliffOffset(x, z, massif + peaks + rugged + carved);
            }
        }
        double foothill = scale * m.foothillHeight * mountain[1] * (0.5 + 0.5 * foothills.sample(x, z));
        double hillNoise = hills.sample(x, z);
        double hillShare = hilly + settings.plains.reliefShare * plain;
        if (hillShare > 0.0) {
            // Desert hills are dunes: the rolling relief flattens out around its middle, so the desert neither sinks
            // into a basin that would fill with a lake nor rises, and the dunes lie on the gentle ground left
            double dry = desert.applyAsDouble(x, z);
            if (dry > 0.0) {
                dry *= smoothstep(0.0, settings.deserts.coastFade, continents.inlandDistance(baseAboveSea));
                hillNoise *= 1.0 - dry * (1.0 - settings.deserts.hillRelief);
            }
        }
        double rolling = scale * hillSettings.height * hillShare * (0.5 + 0.5 * hillNoise);
        double ground = massif + peaks + foothill + isolatedHeight(x, z);

        // A plateau fills the land up to its level instead of adding to it: it rises the same out of plains and hills,
        // and mountains taller than it still stand out of it. Hills keep some of their relief on the table
        double plateauWeight = plateauShare(x, z, baseAboveSea);
        double plateau = 0.0;
        if (plateauWeight > 0.0) {
            double level = scale * (plateaus.levelMin + (plateaus.levelMax - plateaus.levelMin) * (0.5 + 0.5 * plateauHeight.sample(x, z)));
            plateau = plateauWeight * Math.max(0.0, level - baseAboveSea - ground);
            rolling *= 1.0 - plateauWeight * (1.0 - plateaus.tableRelief);
            rolling += plateauWeight * plateauWeight * plateaus.tableRelief * scale * hillSettings.height * (0.5 + 0.5 * hills.sample(x, z)) * plain;
        }

        if (texture != null) {
            texture[0] = land * rugged;
        }
        return land * (ground + plateau + rolling);
    }

    /**
     * How much a plateau holds a position, from 0 to 1. Most of an edge is a gentle slope, and some stretches rise as
     * steep escarpments; both are centered on the same line, so the plateau keeps its size. Plateaus keep back from
     * the coast, like mountains do.
     */
    private double plateauShare(double x, double z, double baseAboveSea) {
        double zoneValue = plateauValue(x, z);
        if (zoneValue <= gentleLow) {
            return 0.0;
        }
        // Only the width of the edge changes along it: blending a steep and a gentle plateau would leave pits and
        // lone blocks where the two disagree, while one outline with a varying slope keeps the plateau whole
        double steepness = smoothstep(steepEdge - EDGE_BLEND, steepEdge + EDGE_BLEND, plateauEdge.sample(x, z));
        double weight = smoothstep(mix(gentleLow, plateauLow, steepness), mix(gentleHigh, plateauHigh, steepness), zoneValue);
        return weight <= 0.0 ? 0.0 : weight * smoothstep(0.0, m.coastBand, continents.inlandDistance(baseAboveSea));
    }

    /** How much a plateau holds a position, from 0 to 1. */
    double plateauShareAt(double x, double z, double baseAboveSea) {
        return plateauShare(x, z, baseAboveSea);
    }

    /** The kind of land at a position: a plateau wherever one holds it, otherwise the tier it mostly belongs to. */
    TerrainLogic.Landform landform(double x, double z, double baseAboveSea) {
        if (plateauShare(x, z, baseAboveSea) > 0.5) {
            return TerrainLogic.Landform.PLATEAU;
        }
        double tier = tier(x, z, baseAboveSea);
        if (smoothstep(zoneLow, zoneHigh, tier) > 0.5) {
            return TerrainLogic.Landform.MOUNTAINS;
        }
        return 1.0 - smoothstep(plainsLow, plainsHigh, tier) > 0.5 ? TerrainLogic.Landform.PLAINS : TerrainLogic.Landform.HILLS;
    }

    /** How far a position lies above the plains, in the hills or mountains, from 0 in the plains to 1 out of them. */
    double uplandShare(double x, double z, double baseAboveSea) {
        return smoothstep(plainsLow, plainsHigh, tier(x, z, baseAboveSea));
    }

    /** How far a position lies in the mountain tier, from 0 in the hills to 1 well inside it. */
    double mountainShare(double x, double z, double baseAboveSea) {
        return smoothstep(zoneLow, zoneHigh, tier(x, z, baseAboveSea));
    }

    /** Whether a position lies in the hill or mountain tier, where rivers can start. */
    boolean isUpland(double x, double z, double baseAboveSea) {
        return tier(x, z, baseAboveSea) >= plainsHigh || plateauShare(x, z, baseAboveSea) > 0.5;
    }

    /**
     * The tier field at a position: low values are plains, middle ones hills, high ones mountains. Near the coast it
     * is held down to the middle of the hills, which turns mountains into hills and leaves plains as they are.
     */
    private double tier(double x, double z, double baseAboveSea) {
        double value = zoneValue(x, z);
        if (value <= hillsMiddle) {
            return value;
        }
        double inland = smoothstep(0.0, m.coastBand, continents.inlandDistance(baseAboveSea));
        return hillsMiddle + inland * (value - hillsMiddle);
    }

    /** The terrain zone field, lowered toward plains where great plains lie. */
    private double zoneValue(double x, double z) {
        double great = smoothstep(greatLow, greatHigh, greatPlains.sample(x, z));
        if (!m.plateTectonics) {
            return zone.sample(x, z) - settings.plains.greatStrength * great;
        }
        double[] plates = plates(x, z);
        double tectonic = plates[0];
        double belt = tectonic > 0.0 ? m.beltStrength * tectonic : m.riftStrength * tectonic;
        return m.zoneNoise * zone.sample(x, z) + m.plateRelief * plates[4] + belt - settings.plains.greatStrength * great;
    }

    /** The plateau zone field: highland plates, the wide flanks of mountain belts, and noise. */
    private double plateauValue(double x, double z) {
        if (!m.plateTectonics) {
            return plateauZone.sample(x, z);
        }
        double[] plates = plates(x, z);
        return plateaus.zoneNoise * plateauZone.sample(x, z) + plateaus.plateHighland * plates[5] + plateaus.beltShare * Math.max(0.0, plates[6]);
    }

    /** The plates around a position, as {@link ContinentLayer#belt} gives them. */
    private double[] plates(double x, double z) {
        return continents.belt(x, z, beltWidth, plateauReach);
    }

    /** How fast plates move together at a position, weighted by closeness to their border: below 0 where they part. */
    private double convergence(double x, double z) {
        return plates(x, z)[0];
    }

    /** Height of the isolated peaks and dome hills around a position. */
    private double isolatedHeight(double x, double z) {
        WorldgenSettings.Isolated config = settings.isolated;
        int cellX = (int) Math.floor(x / isolatedSpacing);
        int cellZ = (int) Math.floor(z / isolatedSpacing);
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

    /** How much of a dome hill stands at a position, from 1 at its top to 0 at its foot. Peaks do not count. */
    double domeShare(double x, double z) {
        int cellX = (int) Math.floor(x / isolatedSpacing);
        int cellZ = (int) Math.floor(z / isolatedSpacing);
        double share = 0.0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Isolated hill = isolated(cellX + dx, cellZ + dz);
                if (hill.height > 0.0 && !hill.peak) {
                    double u = Math.hypot(x - hill.x, z - hill.z) / hill.radius;
                    if (u < 1.0) {
                        share = Math.max(share, 0.5 + 0.5 * Math.cos(Math.PI * u));
                    }
                }
            }
        }
        return share;
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
        double x = (cellX + 0.5 + 0.7 * (Hashing.unit(hash) - 0.5)) * isolatedSpacing;
        double z = (cellZ + 0.5 + 0.7 * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * isolatedSpacing;
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
        int cellX = (int) Math.floor(x / siteSpacing);
        int cellZ = (int) Math.floor(z / siteSpacing);
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
        double x = (cellX + 0.5 + m.siteJitter * (Hashing.unit(hash) - 0.5)) * siteSpacing;
        double z = (cellZ + 0.5 + m.siteJitter * (Hashing.unit(Hashing.mix(hash)) - 0.5)) * siteSpacing;
        double base = continents.height(continents.value(x, z)) - (settings.general.seaLevel - 1);
        double zoneWeight = smoothstep(zoneLow, zoneHigh, tier(x, z, base));
        if (zoneWeight < m.siteMinZone) {
            return new Site(x, z, NONE, 0.0, 0.0, 0.0, new Site[0]);
        }
        double strength = 0.4 + 0.6 * zoneWeight;
        double height = scale * strength * (m.peakMin + (m.peakMax - m.peakMin) * Hashing.unit(Hashing.derive(hash, 2)));
        // Ranges standing on plateaus rise above the design height, the tallest of them up to the surface maximum. They
        // widen with their height, as the slope limit sets their width
        if (plateauPeakGain > 0.0) {
            height *= 1.0 + plateauPeakGain * plateauShare(x, z, base);
        }
        double width = settings.scale.mountains * (m.widthMin + (m.widthMax - m.widthMin) * Hashing.unit(Hashing.derive(hash, 3)));
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
        // In a belt, ranges run along the plate border, as real ones do along the collision that raised them
        double[] belt = m.plateTectonics && m.beltAlignment > 0.0 ? plates(site.x, site.z) : null;
        if (belt != null && belt[0] > 0.0) {
            double along = m.beltAlignment * belt[3] * Math.min(1.0, belt[0] / BELT_FULL);
            double sign = belt[1] * dirX + belt[2] * dirZ >= 0.0 ? 1.0 : -1.0;
            double bx = dirX + along * (sign * belt[1] - dirX);
            double bz = dirZ + along * (sign * belt[2] - dirZ);
            double length = Math.hypot(bx, bz);
            if (length > 1e-9) {
                dirX = bx / length;
                dirZ = bz / length;
            }
        }
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
        // Belts are young mountains, still steep and sharp; old ranges away from plate borders have worn down
        double young = m.plateTectonics && m.beltIgneous > 0.0 ? m.beltIgneous * Math.max(0.0, convergence(x, z)) : 0.0;
        return smoothstep(-STYLE_RANGE, STYLE_RANGE, style.sample(x, z) + young);
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
