package com.regionalapi.api.event;

import com.regionalapi.core.region.RegionDefinition;
import net.minecraft.server.level.ServerPlayer;

/**
 * Functional callback interface fired when a {@link ServerPlayer} transitions between regions.
 */
@FunctionalInterface
public interface PlayerRegionEnterCallback {

    void onRegionEnter(
            ServerPlayer player,
            short previousRegionRawId,
            RegionDefinition previousRegionDefinition,
            short newRegionRawId,
            RegionDefinition newRegionDefinition
    );
}
