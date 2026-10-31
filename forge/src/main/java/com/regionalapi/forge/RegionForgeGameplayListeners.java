package com.regionalapi.forge;

import com.regionalapi.api.RegionalApi;
import com.regionalapi.command.LocateRegionCommand;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import com.regionalapi.platform.CommonRegionMovementTracker;
import com.regionalapi.platform.IPlatformHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityEvent;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Gameplay and lifecycle event listeners for RegionalAPI on Minecraft Forge.
 */
public final class RegionForgeGameplayListeners {

    private static volatile long activeSaltedRegionalSeed = 0L;

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        long rawWorldSeed = server.getWorldData().worldGenOptions().seed();
        activeSaltedRegionalSeed = ClientboundSyncRegionalSeedPacket.computeSaltedRegionalSeed(rawWorldSeed);

        ThreadLocalRegionCache.setActiveWorldSeed(activeSaltedRegionalSeed);
        RegionalApi.getInstance().clearServerCache();
        RegionRegistry.getInstance().freezeAndIndex(server);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        RegionalApi.getInstance().clearServerCache();
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

    @SubscribeEvent
    public void onEntityEnteringSection(EntityEvent.EnteringSection event) {
        if (!event.didChunkChange() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CommonRegionMovementTracker.onPlayerEnterChunk(player, event.getNewPos().x(), event.getNewPos().z());
    }

    @SubscribeEvent
    public void onPlayerTickPost(TickEvent.PlayerTickEvent.Post event) {
        if (event.player instanceof ServerPlayer player) {
            CommonRegionMovementTracker.onPlayerTick(player);
        }
    }

    @SubscribeEvent
    public void onFinalizeMobSpawn(MobSpawnEvent.FinalizeSpawn event) {
        Mob mob = event.getEntity();
        ServerLevelAccessor levelAccessor = event.getLevel();
        int blockX = (int) Math.floor(event.getX());
        int blockY = (int) Math.floor(event.getY());
        int blockZ = (int) Math.floor(event.getZ());

        short regionRawId;
        ServerLevel serverLevel = levelAccessor.getLevel();
        if (serverLevel != null && serverLevel.getServer().isSameThread()) {
            regionRawId = RegionalApi.getInstance().getRegionRawId(serverLevel, blockX, blockZ);
        } else {
            regionRawId = ThreadLocalRegionCache.get().getRegionAtQuart(blockX >> 2, blockZ >> 2);
        }

        RegionDefinition definition = RegionRegistry.getInstance().getByRawId(regionRawId);
        boolean allowed = IPlatformHelper.getInstance().postEntitySpawnInRegion(
                mob,
                levelAccessor,
                blockX,
                blockY,
                blockZ,
                event.getSpawnType(),
                regionRawId,
                definition
        );

        if (!allowed) {
            event.setSpawnCancelled(true);
        }
    }
}
