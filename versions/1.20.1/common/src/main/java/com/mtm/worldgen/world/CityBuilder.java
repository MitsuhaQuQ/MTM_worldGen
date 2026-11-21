package com.mtm.worldgen.world;

import com.mtm.worldgen.platform.Platform;

/**
 * City Builder, the companion mod that plans and builds towns on MTM terrain. When it is installed, the structures that
 * break up the ground it builds on stay away: new worlds are created without villages and sinkholes, and no new village
 * starts in any MTM world.
 */
public final class CityBuilder {
    /** Its mod id. */
    public static final String MOD_ID = "citybuilder";

    private static volatile Boolean present;

    private CityBuilder() {
    }

    /** Whether City Builder is installed. */
    public static boolean present() {
        Boolean value = present;
        if (value == null) {
            value = Platform.get().isModLoaded(MOD_ID);
            present = value;
        }
        return value;
    }
}
