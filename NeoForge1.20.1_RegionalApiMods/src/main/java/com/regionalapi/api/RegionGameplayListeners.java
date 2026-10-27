package com.regionalapi.api;

import com.regionalapi.core.cache.ChunkRegionAttachment;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import com.regionalapi.network.ClientboundSyncRegionalSeedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Zero-overhead gameplay and lifecycle event listeners for RegionalAPI.
 * <ul>
 *     <li><b>Server Startup Freeze</b>: Freezes {@link RegionRegistry}, resolves direct {@code Holder<Biome>}
 *     references, and computes the salted SHA-256 regional seed on {@link ServerAboutToStartEvent}.</li>
 *     <li><b>Movement Tracking Optimization</b>: Listens to {@link EntityEvent.EnteringSection}.
 *     When a player enters a homogeneous chunk, compares the chunk's single {@code short regionId}
 *     against the player's last known {@code regionId} attachment and disables per-tick polling.
 *     Per-tick QuartPos boundary polling is ONLY active while {@code isInBorderChunk() == true}.</li>
 *     <li><b>Zero-GC Spawn Interception</b>: Hooks into {@link FinalizeSpawnEvent} using raw primitive
 *     {@code short} ID lookup without allocating {@code BlockPos} or boxed primitives.</li>
 * </ul>
 */
public final class RegionGameplayListeners {

    private static volatile long activeSaltedRegionalSeed = 0L;

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        long rawWorldSeed = server.getWorldData().worldGenOptions().seed();
        activeSaltedRegionalSeed = ClientboundSyncRegionalSeedPacket.computeSaltedRegionalSeed(rawWorldSeed);

        // Use the salted regional seed as the deterministic spatial seed so client minimaps and server match
        ThreadLocalRegionCache.setActiveWorldSeed(activeSaltedRegionalSeed);
        RegionalApi.getInstance().clearServerCache();
        RegionRegistry.getInstance().freezeAndIndex(server);
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        RegionalApi.getInstance().clearServerCache();
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
            PacketDistributor.sendToPlayer(
                    serverPlayer,
                    new ClientboundSyncRegionalSeedPacket(activeSaltedRegionalSeed)
            );
            evaluatePlayerChunkEntry(
                    serverPlayer,
                    serverPlayer.getBlockX() >> 4,
                    serverPlayer.getBlockZ() >> 4
            );
        }
    }

    /**
     * Movement Tracking Optimization: evaluates chunk homogeneity when a player crosses a chunk section boundary.
     * If {@code isHomogeneous == true}, resolves region transition in O(1) and disables per-tick polling.
     */
    @SubscribeEvent
    public void onEntityEnteringSection(EntityEvent.EnteringSection event) {
        if (!event.didChunkChange()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int newChunkX = event.getNewPos().x();
        int newChunkZ = event.getNewPos().z();
        evaluatePlayerChunkEntry(player, newChunkX, newChunkZ);
    }

    /**
     * Conditional Border-Chunk Polling: returns immediately at the first boolean check when the player
     * is inside a homogeneous chunk (`!tracker.isInBorderChunk()`). Only evaluates QuartPos boundaries
     * when the player is actively inside a border chunk (`isHomogeneous == false`) and has moved into
     * a different 4x4 QuartPos cell.
     */
    @SubscribeEvent
    public void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        ChunkRegionAttachment.PlayerRegionTracker tracker =
                player.getExistingData(ChunkRegionAttachment.PLAYER_REGION_TRACKER).orElse(null);
        if (tracker == null || !tracker.isInBorderChunk()) {
            return;
        }

        int currentQuartX = player.getBlockX() >> 2;
        int currentQuartZ = player.getBlockZ() >> 2;
        if (currentQuartX == tracker.getLastQuartX() && currentQuartZ == tracker.getLastQuartZ()) {
            return;
        }

        tracker.updateLastQuartPos(currentQuartX, currentQuartZ);
        ServerLevel serverLevel = player.serverLevel();
        short newRegionId = RegionalApi.getInstance().getRegionRawId(
                serverLevel,
                player.getBlockX(),
                player.getBlockZ()
        );
        checkAndFirePlayerRegionTransition(player, tracker, newRegionId);
    }

    /**
     * Zero-GC Entity Spawn Interception on {@link FinalizeSpawnEvent}.
     */
    @SubscribeEvent
    public void onFinalizeMobSpawn(FinalizeSpawnEvent event) {
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
        RegionEvent.EntitySpawnInRegionEvent regionSpawnEvent = new RegionEvent.EntitySpawnInRegionEvent(
                mob,
                levelAccessor,
                blockX,
                blockY,
                blockZ,
                event.getSpawnType(),
                regionRawId,
                definition
        );

        if (NeoForge.EVENT_BUS.post(regionSpawnEvent).isCanceled()) {
            event.setSpawnCancelled(true);
        }
    }

    private static void evaluatePlayerChunkEntry(ServerPlayer player, int chunkX, int chunkZ) {
        ServerLevel level = player.serverLevel();
        ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        ChunkRegionAttachment.PlayerRegionTracker tracker =
                player.getData(ChunkRegionAttachment.PLAYER_REGION_TRACKER.get());

        int qx = player.getBlockX() >> 2;
        int qz = player.getBlockZ() >> 2;
        tracker.updateLastQuartPos(qx, qz);

        if (chunk != null) {
            ChunkRegionAttachment.ChunkRegionData chunkData = ChunkRegionAttachment.getOrPopulate(chunk);
            if (chunkData.isHomogeneous()) {
                tracker.setInBorderChunk(false);
                checkAndFirePlayerRegionTransition(player, tracker, chunkData.getSingleRegionId());
            } else {
                tracker.setInBorderChunk(true);
                short currentRegionId = chunkData.getRegionAtQuart(qx, qz);
                checkAndFirePlayerRegionTransition(player, tracker, currentRegionId);
            }
        } else {
            ThreadLocalRegionCache cache = ThreadLocalRegionCache.get();
            int slot = cache.ensureChunkSlot(chunkX, chunkZ);
            boolean homogeneous = cache.isSlotHomogeneous(slot);
            tracker.setInBorderChunk(!homogeneous);
            short currentRegionId = homogeneous
                    ? cache.getSlotFirstRegionId(slot)
                    : cache.getRegionAtQuart(qx, qz);
            checkAndFirePlayerRegionTransition(player, tracker, currentRegionId);
        }
    }

    private static void checkAndFirePlayerRegionTransition(
            ServerPlayer player,
            ChunkRegionAttachment.PlayerRegionTracker tracker,
            short newRegionId
    ) {
        short previousRegionId = tracker.getLastRegionId();
        if (previousRegionId != newRegionId) {
            tracker.setLastRegionId(newRegionId);
            RegionRegistry registry = RegionRegistry.getInstance();
            RegionDefinition previousDef = (previousRegionId >= 0) ? registry.getByRawId(previousRegionId) : null;
            RegionDefinition newDef = registry.getByRawId(newRegionId);
            NeoForge.EVENT_BUS.post(new RegionEvent.PlayerEnterRegionEvent(
                    player,
                    previousRegionId,
                    previousDef,
                    newRegionId,
                    newDef
            ));
        }
    }
}
