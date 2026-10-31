package com.regionalapi.platform;

import com.regionalapi.core.region.RegionDefinition;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;

import java.util.ServiceLoader;

/**
 * Multi-loader service provider interface (SPI) for loader-dependent operations.
 */
public interface IPlatformHelper {

    static IPlatformHelper getInstance() {
        return PlatformHelperHolder.INSTANCE;
    }

    /**
     * Checks if a mod is loaded on the current platform.
     *
     * @param modId The mod identifier.
     * @return {@code true} if present.
     */
    boolean isModLoaded(String modId);

    /**
     * Transmits the salted regional seed packet to the connecting player.
     *
     * @param player             The recipient player.
     * @param saltedRegionalSeed The salted regional world seed.
     */
    void sendSyncSeedPacket(ServerPlayer player, long saltedRegionalSeed);

    /**
     * Dispatches the player region transition event to the platform's native event bus or callbacks.
     */
    void postPlayerRegionEnter(
            ServerPlayer player,
            short previousRegionRawId,
            RegionDefinition previousRegionDefinition,
            short newRegionRawId,
            RegionDefinition newRegionDefinition
    );

    /**
     * Dispatches the entity spawn event to the platform's native event bus or callbacks.
     *
     * @return {@code true} if spawn is allowed, {@code false} if cancelled.
     */
    boolean postEntitySpawnInRegion(
            Mob mob,
            ServerLevelAccessor levelAccessor,
            int blockX,
            int blockY,
            int blockZ,
            MobSpawnType spawnType,
            short regionRawId,
            RegionDefinition definition
    );

    final class PlatformHelperHolder {
        private static final IPlatformHelper INSTANCE = ServiceLoader.load(IPlatformHelper.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to load IPlatformHelper service implementation!"));
    }
}
