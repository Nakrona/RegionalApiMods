package com.regionalapi.platform;

import com.regionalapi.api.RegionalApi;
import com.regionalapi.core.cache.ChunkRegionAttachment;
import com.regionalapi.core.cache.IPlayerRegionTracker;
import com.regionalapi.core.cache.IRegionChunkData;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Universal Zero-GC player region movement evaluator shared across all loaders.
 * <p>
 * Because every 16x16 chunk is guaranteed homogeneous (at most 1 region per chunk),
 * region transitions only occur when crossing chunk boundaries.
 */
public final class CommonRegionMovementTracker {

    private CommonRegionMovementTracker() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Evaluates player chunk transition.
     * Always resolves coordinates strictly from the player's true current block position as the
     * single source of truth, guaranteeing every 16x16 chunk resolves to a single homogeneous region.
     */
    public static void onPlayerEnterChunk(ServerPlayer player, int chunkX, int chunkZ) {
        if (!(player instanceof IPlayerRegionTracker tracker)) {
            return;
        }

        int blockX = player.getBlockX();
        int blockZ = player.getBlockZ();
        int actualChunkX = blockX >> 4;
        int actualChunkZ = blockZ >> 4;
        int qx = blockX >> 2;
        int qz = blockZ >> 2;
        tracker.regional$updateLastQuartPos(qx, qz);
        tracker.regional$setInBorderChunk(false);

        ServerLevel level = player.serverLevel();
        ChunkAccess chunk = level.getChunkSource().getChunkNow(actualChunkX, actualChunkZ);

        short currentRegionId;
        if (chunk != null) {
            IRegionChunkData chunkData = ChunkRegionAttachment.getOrPopulate(chunk);
            currentRegionId = chunkData.regional$getSingleRegionId();
        } else {
            ThreadLocalRegionCache cache = ThreadLocalRegionCache.get();
            int slot = cache.ensureChunkSlot(actualChunkX, actualChunkZ);
            currentRegionId = cache.getSlotFirstRegionId(slot);
        }

        checkAndFirePlayerRegionTransition(player, tracker, currentRegionId);
    }

    /**
     * Retained for binary backward compatibility. Returns immediately with zero overhead
     * because every chunk is guaranteed homogeneous (`inBorderChunk` is always `false`).
     */
    public static void onPlayerTick(ServerPlayer player) {
        if (!(player instanceof IPlayerRegionTracker tracker) || !tracker.regional$isInBorderChunk()) {
            return;
        }

        int blockX = player.getBlockX();
        int blockZ = player.getBlockZ();
        int currentQuartX = blockX >> 2;
        int currentQuartZ = blockZ >> 2;
        if (currentQuartX == tracker.regional$getLastQuartX() && currentQuartZ == tracker.regional$getLastQuartZ()) {
            return;
        }

        tracker.regional$updateLastQuartPos(currentQuartX, currentQuartZ);
        ServerLevel serverLevel = player.serverLevel();
        short newRegionId = RegionalApi.getInstance().getRegionRawId(
                serverLevel,
                blockX,
                blockZ
        );
        checkAndFirePlayerRegionTransition(player, tracker, newRegionId);
    }

    private static void checkAndFirePlayerRegionTransition(
            ServerPlayer player,
            IPlayerRegionTracker tracker,
            short newRegionId
    ) {
        short previousRegionId = tracker.regional$getLastRegionId();
        if (previousRegionId != newRegionId) {
            tracker.regional$setLastRegionId(newRegionId);
            RegionRegistry registry = RegionRegistry.getInstance();
            RegionDefinition previousDef = (previousRegionId >= 0) ? registry.getByRawId(previousRegionId) : null;
            RegionDefinition newDef = registry.getByRawId(newRegionId);
            IPlatformHelper.getInstance().postPlayerRegionEnter(
                    player,
                    previousRegionId,
                    previousDef,
                    newRegionId,
                    newDef
            );
            if (newDef != null) {
                IPlatformHelper.getInstance().sendSyncPlayerRegionPacket(player, newDef.id());
            }
        }
    }
}
