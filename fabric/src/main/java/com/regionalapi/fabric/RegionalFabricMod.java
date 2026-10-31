package com.regionalapi.fabric;

import com.regionalapi.RegionalModConstants;
import com.regionalapi.api.RegionalApi;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionRegistry;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.CommonRegionMovementTracker;
import com.regionalapi.platform.IPlatformHelper;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Main Fabric entry point for **RegionalAPI** (`regionalapi`).
 */
public final class RegionalFabricMod implements ModInitializer {

    private static volatile long activeSaltedRegionalSeed = 0L;

    @Override
    public void onInitialize() {
        // 1. Register S2C payload type
        PayloadTypeRegistry.playS2C().register(
                ClientboundSyncRegionalSeedPacket.TYPE,
                ClientboundSyncRegionalSeedPacket.STREAM_CODEC
        );

        // 2. Server lifecycle listeners
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            long rawWorldSeed = server.getWorldData().worldGenOptions().seed();
            activeSaltedRegionalSeed = ClientboundSyncRegionalSeedPacket.computeSaltedRegionalSeed(rawWorldSeed);

            ThreadLocalRegionCache.setActiveWorldSeed(activeSaltedRegionalSeed);
            RegionalApi.getInstance().clearServerCache();
            RegionRegistry.getInstance().freezeAndIndex(server);
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            RegionalApi.getInstance().clearServerCache();
        });

        // 3. Player login listener
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            IPlatformHelper.getInstance().sendSyncSeedPacket(player, activeSaltedRegionalSeed);
            CommonRegionMovementTracker.onPlayerEnterChunk(
                    player,
                    player.getBlockX() >> 4,
                    player.getBlockZ() >> 4
            );
        });

        // 4. Chunk unload cache eviction
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            RegionalApi.getInstance().evictChunkFromServerCache(chunk.getPos().toLong());
        });

        RegionalModConstants.LOGGER.info("RegionalAPI Fabric initialized (2D Laguerre-Voronoi & 3D Biome Masking Engine).");
    }
}
