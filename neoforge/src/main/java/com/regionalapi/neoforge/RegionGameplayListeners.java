package com.regionalapi.neoforge;

import com.regionalapi.api.RegionalApi;
import com.regionalapi.command.LocateRegionCommand;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionRegistry;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.CommonRegionMovementTracker;
import com.regionalapi.platform.IPlatformHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Zero-overhead gameplay and lifecycle event listeners for RegionalAPI on NeoForge.
 */
public final class RegionGameplayListeners {

    private static volatile long activeSaltedRegionalSeed = 0L;

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        long rawWorldSeed = server.getWorldData().worldGenOptions().seed();
        activeSaltedRegionalSeed = ClientboundSyncRegionalSeedPacket.computeSaltedRegionalSeed(rawWorldSeed);

        // Use the salted regional seed as the deterministic spatial seed so client minimaps and server match
        ThreadLocalRegionCache.setAuthoritativeRegionalSeed(activeSaltedRegionalSeed);
        RegionalApi.getInstance().clearServerCache();
        RegionRegistry.getInstance().freezeAndIndex(server);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        RegionalApi.getInstance().clearServerCache();
        ThreadLocalRegionCache.resetSeed();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        LocateRegionCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            RegionalApi.getInstance().evictChunkFromServerCache(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            IPlatformHelper.getInstance().sendSyncSeedPacket(serverPlayer, activeSaltedRegionalSeed);
            CommonRegionMovementTracker.onPlayerEnterChunk(
                    serverPlayer,
                    serverPlayer.getBlockX() >> 4,
                    serverPlayer.getBlockZ() >> 4
            );
        }
    }
}
