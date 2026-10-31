package com.regionalapi.fabric;

import com.regionalapi.api.event.EntitySpawnInRegionCallback;
import com.regionalapi.api.event.PlayerRegionEnterCallback;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Fabric event registry for RegionalAPI callbacks.
 */
public final class FabricRegionalEvents {

    public static final Event<PlayerRegionEnterCallback> PLAYER_ENTER_REGION = EventFactory.createArrayBacked(
            PlayerRegionEnterCallback.class,
            callbacks -> (player, previousRegionRawId, previousRegionDefinition, newRegionRawId, newRegionDefinition) -> {
                for (PlayerRegionEnterCallback callback : callbacks) {
                    callback.onRegionEnter(player, previousRegionRawId, previousRegionDefinition, newRegionRawId, newRegionDefinition);
                }
            }
    );

    public static final Event<EntitySpawnInRegionCallback> ENTITY_SPAWN_IN_REGION = EventFactory.createArrayBacked(
            EntitySpawnInRegionCallback.class,
            callbacks -> (mob, levelAccessor, blockX, blockY, blockZ, spawnType, regionRawId, definition) -> {
                for (EntitySpawnInRegionCallback callback : callbacks) {
                    if (!callback.shouldSpawn(mob, levelAccessor, blockX, blockY, blockZ, spawnType, regionRawId, definition)) {
                        return false;
                    }
                }
                return true;
            }
    );

    private FabricRegionalEvents() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }
}
