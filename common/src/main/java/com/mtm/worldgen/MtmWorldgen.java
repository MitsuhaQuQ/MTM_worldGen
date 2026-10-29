package com.mtm.worldgen;

import com.mtm.worldgen.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-independent entry point. All terrain logic lives in this module;
 * loader modules only register, hook events and adapt platform differences.
 */
public final class MtmWorldgen {
    public static final String MOD_ID = "mtm_worldgen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private MtmWorldgen() {
    }

    /** Called once by each loader's entry point during mod construction. */
    public static void init() {
        LOGGER.info("MTM Worldgen initializing on {}", Platform.get().loaderName());
    }
}
