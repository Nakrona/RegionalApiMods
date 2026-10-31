package com.regionalapi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global constant definitions and logger for RegionalAPI.
 */
public final class RegionalModConstants {

    public static final String MOD_ID = "regionalapi";
    public static final String MOD_NAME = "RegionalAPI";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private RegionalModConstants() {
        throw new UnsupportedOperationException("Constants class cannot be instantiated.");
    }
}
