package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;
import com.mtm.worldgen.noise.ValueNoise;

/**
 * Overhangs on high steep faces. Rock at a given height takes the surface from a little way up or down the slope, so
 * parts of a wall jut out over the air and others recede under them. Only the blocks change: everything that reads
 * the height map, such as rivers and biomes, still sees the plain slope.
 */
final class OverhangLayer {
    private static final long OVERHANG_SALT = 0x4F56455248L;
    /** Surface samples along the slope, an odd number with the plain surface in the middle. */
    static final int PROFILE_SIZE = 5;

    private final WorldgenSettings.Overhangs config;
    private final Fbm patches;
    private final long shiftSeed;
    private final double minHeight;

    OverhangLayer(long seed, WorldgenSettings settings) {
        this.config = settings.overhangs;
        this.patches = new Fbm(Hashing.derive(seed, OVERHANG_SALT), 3, config.patchWavelength);
        this.shiftSeed = Hashing.derive(seed, OVERHANG_SALT + 1);
        this.minHeight = settings.general.seaLevel + settings.heightScale() * config.minHeight;
    }

    /** Share of the full reach at a position, before the slope is known. Cheap, and zero on most of the world. */
    double patch(double x, double z, double height) {
        if (height < minHeight) {
            return 0.0;
        }
        return smoothstep(config.patchLow, config.patchHigh, patches.sample(x, z));
    }

    /** Share of the full reach on a slope of the given grade, as a fraction. */
    double slope(double grade) {
        return smoothstep(config.slopeLow, config.slopeHigh, grade * 100.0);
    }

    /** How far along the slope a block looks for its surface, from -1 (down) to 1 (up). */
    double shift(double x, double y, double z) {
        double noise = ValueNoise.sample(shiftSeed, x / config.wavelength, y / config.verticalWavelength,
                z / config.wavelength);
        return Math.max(-1.0, Math.min(1.0, noise * config.sharpness));
    }

    private static double smoothstep(double low, double high, double value) {
        double t = Math.max(0.0, Math.min(1.0, (value - low) / (high - low)));
        return t * t * (3.0 - 2.0 * t);
    }
}
