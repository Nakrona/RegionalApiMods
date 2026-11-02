package com.regionalapi.forge;

import com.regionalapi.api.RegionalApi;
import com.regionalapi.command.LocateRegionCommand;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionRegistry;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.CommonRegionMovementTracker;
import com.regionalapi.platform.IPlatformHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Gameplay and lifecycle event listeners for RegionalAPI on Minecraft Forge.
 */
public final class RegionForgeGameplayListeners {

    private static volatile long activeSaltedRegionalSeed = 0L;

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        long rawWorldSeed = server.getWorldData().worldGenOptions().seed();
        activeSaltedRegionalSeed = ClientboundSyncRegionalSeedPacket.computeSaltedRegionalSeed(rawWorldSeed);

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
