package com.regionalapi.core.cache;

/**
 * High-performance Duck Interface injected directly into {@link net.minecraft.world.level.chunk.ChunkAccess}.
 * <p>
 * Eliminates platform-specific data attachments by providing zero-GC primitive fields for chunk regional state.
 * Serialized directly via {@link net.minecraft.world.level.chunk.storage.ChunkSerializer}.
 */
public interface IRegionChunkData {

    /**
     * @return {@code true} if this chunk's regional data has been computed and populated.
     */
    boolean regional$isInitialized();

    /**
     * @return {@code true} if every block in this chunk belongs to the same region.
     */
    boolean regional$isHomogeneous();

    /**
     * @return The single primitive {@code short} region ID if homogeneous, or first quart ID if border.
     */
    short regional$getSingleRegionId();

    /**
     * @return The 16-element primitive {@code short} array for 4x4 QuartPos resolution, or {@code null} if homogeneous.
     */
    short[] regional$getBorderQuartGrid();

    /**
     * Resolves the region ID at the given horizontal QuartPos coordinates {@code (qx, qz)} in O(1) time.
     *
     * @param qx Quart X coordinate (blockX >> 2).
     * @param qz Quart Z coordinate (blockZ >> 2).
     * @return The primitive {@code short} region ID.
     */
    short regional$getRegionAtQuart(int qx, int qz);

    /**
     * Sets this chunk as a homogeneous territory under the given region ID.
     *
     * @param regionId The territory's primitive {@code short} region ID.
     */
    void regional$setHomogeneous(short regionId);

    /**
     * Sets this chunk as a border chunk with heterogeneous 4x4 QuartPos distribution.
     *
     * @param quartGrid16 Array of 16 primitive {@code short} region IDs.
     */
    void regional$setBorder(short[] quartGrid16);
}
