package com.mtm.worldgen.logic;

import com.mtm.worldgen.noise.Fbm;
import com.mtm.worldgen.noise.Hashing;

/** Pipeline step 7: coast types, which change along the shore in long stretches. */
final class CoastLayer {
    enum Type {
        SAND, GRAVEL, ROCK, CLIFF
    }

    private static final long TYPE_SALT = 0x434F415354L;
    /** Stretches fractal noise, which clusters around zero, so shares of the range match shares of the coast. */
    private static final double CONTRAST = 2.2;
    /** Span of the type value over which a cliff grows to full height along the coast. */
    private static final double CLIFF_FADE = 0.05;

    private final WorldgenSettings.Beaches config;
    private final Fbm noise;

    CoastLayer(long seed, WorldgenSettings settings) {
        this.config = settings.beaches;
        this.noise = new Fbm(Hashing.derive(seed, TYPE_SALT), 2, config.typeWavelength);
    }

    private double value(double x, double z) {
        return 0.5 + 0.5 * Math.max(-1.0, Math.min(1.0, CONTRAST * noise.sample(x, z)));
    }

    Type type(double x, double z) {
        double value = value(x, z);
        if (value < config.sandShare) {
            return Type.SAND;
        }
        if (value < config.sandShare + config.gravelShare) {
            return Type.GRAVEL;
        }
        return value < config.sandShare + config.gravelShare + config.rockShare ? Type.ROCK : Type.CLIFF;
    }

    /** Share of the full cliff height at a position along the coast, from 0 to 1. */
    double cliffStrength(double x, double z) {
        double start = config.sandShare + config.gravelShare + config.rockShare;
        return Math.max(0.0, Math.min(1.0, (value(x, z) - start) / CLIFF_FADE));
    }
}
