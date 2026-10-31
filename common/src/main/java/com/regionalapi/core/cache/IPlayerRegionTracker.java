package com.regionalapi.core.cache;

/**
 * High-performance Duck Interface injected directly into {@link net.minecraft.server.level.ServerPlayer}.
 * <p>
 * Eliminates platform-specific data attachments by providing zero-overhead primitive tracking
 * for the player's last known region and border-chunk status.
 */
public interface IPlayerRegionTracker {

    /**
     * @return The player's last known primitive {@code short} region ID (-1 if uninitialized).
     */
    short regional$getLastRegionId();

    /**
     * Updates the player's last known primitive {@code short} region ID.
     */
    void regional$setLastRegionId(short regionId);

    /**
     * @return {@code true} if the player currently resides in a border chunk requiring QuartPos polling.
     */
    boolean regional$isInBorderChunk();

    /**
     * Updates whether the player is currently inside a border chunk.
     */
    void regional$setInBorderChunk(boolean inBorderChunk);

    /**
     * @return The last evaluated Quart X coordinate (blockX >> 2).
     */
    int regional$getLastQuartX();

    /**
     * @return The last evaluated Quart Z coordinate (blockZ >> 2).
     */
    int regional$getLastQuartZ();

    /**
     * Updates the last evaluated QuartPos coordinates.
     */
    void regional$updateLastQuartPos(int qx, int qz);
}
