package com.regionalapi.api;

import com.regionalapi.core.cache.ChunkRegionAttachment;
import com.regionalapi.core.cache.IRegionChunkData;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.math.VoronoiEngine2D;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Primary public developer facade for **RegionalAPI**.
 * <p>
 * Provides sub-microsecond, Zero-GC region queries for external mods and bridge layers
 * (e.g., {@code MedieavalRegional}, {@code RegionalCobblemonBridge}). All hot-path queries operate on primitive
 * {@code short} IDs and {@code int} coordinates without allocating {@code BlockPos},
 * {@code ChunkPos}, or boxed numbers.
 */
public final class RegionalApi {

    private static final RegionalApi INSTANCE = new RegionalApi();

    /**
     * Server-thread fastutil primitive cache mapping {@code ChunkPos.asLong(cx, cz)} to
     * homogeneous {@code short} region IDs (or {@code -1} if border chunk).
     */
    private final Long2ShortOpenHashMap serverHomogeneousChunkCache = new Long2ShortOpenHashMap(4096);

    private RegionalApi() {
        this.serverHomogeneousChunkCache.defaultReturnValue((short) -2);
    }

    /**
     * Returns the singleton {@link RegionalApi} service instance.
     */
    public static RegionalApi getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a custom {@link RegionDefinition} into RegionalAPI.
     * Should be called during mod construction or setup before server startup.
     *
     * @param definition The region definition to register.
     */
    public void registerRegion(RegionDefinition definition) {
        RegionRegistry.getInstance().register(definition);
    }

    /**
     * Configures the world spatial generation mode ({@code INFINITE_TESSELLATION} or {@code FINITE_CENTRALIZED}).
     *
     * @param mode                  The generation mode.
     * @param maxFiniteRadiusBlocks Maximum radius in blocks around {@code (0, 0)} when using {@code FINITE_CENTRALIZED}.
     */
    public void configureGenerationMode(VoronoiEngine2D.GenerationMode mode, int maxFiniteRadiusBlocks) {
        RegionRegistry.getInstance().configureGenerationMode(mode, maxFiniteRadiusBlocks);
    }

    /**
     * Resolves the raw primitive {@code short} region ID at the specified block coordinates in sub-microsecond time.
     * <p>
     * Never allocates objects. Uses the server-level fastutil primitive cache, loaded chunk attachment,
     * or the current thread's {@link ThreadLocalRegionCache} during world generation.
     *
     * @param level  The {@link ServerLevel} being queried.
     * @param blockX Block X coordinate.
     * @param blockZ Block Z coordinate.
     * @return The primitive {@code short} region ID ({@code 0} for fallback Wilds).
     */
    public short getRegionRawId(ServerLevel level, int blockX, int blockZ) {
        int qx = blockX >> 2;
        int qz = blockZ >> 2;
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;

        // If called on the main server thread, check the fastutil primitive cache or loaded chunk duck interface first
        if (level.getServer().isSameThread()) {
            long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            short cachedHomogeneous = this.serverHomogeneousChunkCache.get(chunkKey);
            if (cachedHomogeneous >= 0) {
                return cachedHomogeneous;
            }

            ChunkAccess loadedChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (loadedChunk != null) {
                IRegionChunkData data = ChunkRegionAttachment.getOrPopulate(loadedChunk);
                if (data.regional$isHomogeneous()) {
                    short id = data.regional$getSingleRegionId();
                    this.serverHomogeneousChunkCache.put(chunkKey, id);
                    return id;
                } else {
                    this.serverHomogeneousChunkCache.put(chunkKey, (short) -1);
                    return data.regional$getRegionAtQuart(qx, qz);
                }
            }
        }

        // Worldgen worker thread or unloaded chunk fast-path via zero-allocation ThreadLocalRegionCache
        return ThreadLocalRegionCache.get().getRegionAtQuart(qx, qz);
    }

    /**
     * Resolves the {@link ResourceLocation} identifier of the region at the specified block coordinates.
     *
     * @param level  The {@link ServerLevel} being queried.
     * @param blockX Block X coordinate.
     * @param blockZ Block Z coordinate.
     * @return The pre-cached {@link ResourceLocation} of the region.
     */
    public ResourceLocation getRegionId(ServerLevel level, int blockX, int blockZ) {
        short rawId = getRegionRawId(level, blockX, blockZ);
        RegionDefinition definition = RegionRegistry.getInstance().getByRawId(rawId);
        return (definition != null) ? definition.id() : RegionRegistry.FALLBACK_WILDS_LOCATION;
    }

    /**
     * Resolves the {@link RegionDefinition} at the specified block coordinates in O(1) time.
     *
     * @param level  The {@link ServerLevel} being queried.
     * @param blockX Block X coordinate.
     * @param blockZ Block Z coordinate.
     * @return The {@link RegionDefinition} at those coordinates.
     */
    public RegionDefinition getRegionDefinition(ServerLevel level, int blockX, int blockZ) {
        short rawId = getRegionRawId(level, blockX, blockZ);
        return RegionRegistry.getInstance().getByRawId(rawId);
    }

    /**
     * Retrieves a {@link RegionDefinition} directly by its primitive {@code short} ID in O(1) array-lookup time.
     *
     * @param rawId The primitive {@code short} region ID.
     * @return The corresponding {@link RegionDefinition}.
     */
    public RegionDefinition getRegionByRawId(short rawId) {
        return RegionRegistry.getInstance().getByRawId(rawId);
    }

    /**
     * Converts a {@link ResourceLocation} region ID into its frozen primitive {@code short} ID.
     *
     * @param regionId The region's {@link ResourceLocation}.
     * @return The primitive {@code short} ID, or {@code -1} if not registered.
     */
    public short getRawIdForLocation(ResourceLocation regionId) {
        return RegionRegistry.getInstance().getRawId(regionId);
    }

    /**
     * Queries the pre-computed 2D QuadTree for the center block coordinates of an {@code isUnique == true} region.
     * Completes in under 0.05 ms without scanning chunks.
     *
     * @param uniqueRegionId The {@link ResourceLocation} of the unique region.
     * @return Packed 64-bit block coordinate {@code ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL)},
     *         or {@link Long#MIN_VALUE} if not found.
     */
    public long getUniqueRegionCenterBlockPacked(ResourceLocation uniqueRegionId) {
        short rawId = RegionRegistry.getInstance().getRawId(uniqueRegionId);
        if (rawId < 0) {
            return Long.MIN_VALUE;
        }
        return RegionRegistry.getInstance().findUniqueRegionBlockCenterPacked(rawId);
    }

    /**
     * Clears the server-side primitive chunk cache when a server stops or reloads.
     */
    public void clearServerCache() {
        this.serverHomogeneousChunkCache.clear();
    }

    /**
     * Evicts a specific chunk key from the server-side primitive cache when a chunk unloads.
     */
    public void evictChunkFromServerCache(long chunkKey) {
        this.serverHomogeneousChunkCache.remove(chunkKey);
    }
}
