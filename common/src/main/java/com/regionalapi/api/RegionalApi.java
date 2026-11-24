package com.regionalapi.api;

import com.regionalapi.core.cache.ChunkRegionAttachment;
import com.regionalapi.core.cache.IRegionChunkData;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.math.VoronoiEngine2D;
import com.regionalapi.core.region.RegionContentRegistry;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Collection;
import java.util.Set;

/**
 * Primary public developer facade for **RegionalAPI**.
 * <p>
 * Provides sub-microsecond, Zero-GC region queries for external mods and bridge layers
 * (e.g., {@code MedieavalRegional}, {@code RegionalCobblemonBridge}). All hot-path queries operate on primitive
 * {@code short} IDs and {@code int} coordinates without allocating {@code BlockPos},
 * {@code ChunkPos}, or boxed numbers. Every 16x16 chunk resolves to at most 1 homogeneous region.
 */
public final class RegionalApi {

    private static final RegionalApi INSTANCE = new RegionalApi();

    /**
     * Server-thread fastutil primitive cache mapping {@code ChunkPos.asLong(cx, cz)} to
     * homogeneous {@code short} region IDs.
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

    // -------------------------------------------------------------------------
    // Optional Declarative Region Content Metadata & Introspection API
    // -------------------------------------------------------------------------

    /**
     * Optionally declares a biome {@link ResourceLocation} associated with {@code regionId}
     * for inter-addon introspection and region-scoped {@code /locate region <region> biome} tab-completion.
     *
     * @param regionId The target region identifier.
     * @param biomeId  The biome identifier associated with the region.
     */
    public void registerRegionBiome(ResourceLocation regionId, ResourceLocation biomeId) {
        RegionContentRegistry.getInstance().registerBiome(regionId, biomeId);
    }

    /**
     * Optionally declares multiple biome {@link ResourceLocation} IDs associated with {@code regionId}.
     *
     * @param regionId The target region identifier.
     * @param biomeIds Collection of biome identifiers associated with the region.
     */
    public void registerRegionBiomes(ResourceLocation regionId, Collection<ResourceLocation> biomeIds) {
        RegionContentRegistry.getInstance().registerBiomes(regionId, biomeIds);
    }

    /**
     * Optionally declares a biome {@link TagKey} associated with {@code regionId}.
     *
     * @param regionId The target region identifier.
     * @param biomeTag The biome tag associated with the region.
     */
    public void registerRegionBiomeTag(ResourceLocation regionId, TagKey<Biome> biomeTag) {
        RegionContentRegistry.getInstance().registerBiomeTag(regionId, biomeTag);
    }

    /**
     * Optionally declares multiple biome {@link TagKey} entries associated with {@code regionId}.
     *
     * @param regionId  The target region identifier.
     * @param biomeTags Collection of biome tags associated with the region.
     */
    public void registerRegionBiomeTags(ResourceLocation regionId, Collection<TagKey<Biome>> biomeTags) {
        RegionContentRegistry.getInstance().registerBiomeTags(regionId, biomeTags);
    }

    /**
     * Optionally declares a structure {@link ResourceLocation} associated with {@code regionId}
     * for inter-addon introspection and region-scoped {@code /locate region <region> structure} tab-completion.
     *
     * @param regionId    The target region identifier.
     * @param structureId The structure identifier associated with the region.
     */
    public void registerRegionStructure(ResourceLocation regionId, ResourceLocation structureId) {
        RegionContentRegistry.getInstance().registerStructure(regionId, structureId);
    }

    /**
     * Optionally declares multiple structure {@link ResourceLocation} IDs associated with {@code regionId}.
     *
     * @param regionId     The target region identifier.
     * @param structureIds Collection of structure identifiers associated with the region.
     */
    public void registerRegionStructures(ResourceLocation regionId, Collection<ResourceLocation> structureIds) {
        RegionContentRegistry.getInstance().registerStructures(regionId, structureIds);
    }

    /**
     * Optionally declares a structure {@link TagKey} associated with {@code regionId}.
     *
     * @param regionId     The target region identifier.
     * @param structureTag The structure tag associated with the region.
     */
    public void registerRegionStructureTag(ResourceLocation regionId, TagKey<Structure> structureTag) {
        RegionContentRegistry.getInstance().registerStructureTag(regionId, structureTag);
    }

    /**
     * Optionally declares multiple structure {@link TagKey} entries associated with {@code regionId}.
     *
     * @param regionId      The target region identifier.
     * @param structureTags Collection of structure tags associated with the region.
     */
    public void registerRegionStructureTags(ResourceLocation regionId, Collection<TagKey<Structure>> structureTags) {
        RegionContentRegistry.getInstance().registerStructureTags(regionId, structureTags);
    }

    /**
     * Returns an unmodifiable set of biome IDs registered for {@code regionId}, automatically
     * including any surface/cave biome IDs declared by the region's {@link com.regionalapi.core.region.BiomeMaskRule}.
     *
     * @param regionId The region identifier to inspect.
     * @return Unmodifiable set of biome identifiers (empty if none declared).
     */
    public Set<ResourceLocation> getRegisteredRegionBiomes(ResourceLocation regionId) {
        return RegionContentRegistry.getInstance().getRegisteredBiomes(regionId);
    }

    /**
     * Returns an unmodifiable set of biome {@link TagKey} entries explicitly registered for {@code regionId}.
     *
     * @param regionId The region identifier to inspect.
     * @return Unmodifiable set of biome tags (empty if none declared).
     */
    public Set<TagKey<Biome>> getRegisteredRegionBiomeTags(ResourceLocation regionId) {
        return RegionContentRegistry.getInstance().getRegisteredBiomeTags(regionId);
    }

    /**
     * Returns {@code true} if {@code regionId} has any registered biomes/tags or a {@link com.regionalapi.core.region.BiomeMaskRule}.
     */
    public boolean hasRegisteredRegionBiomes(ResourceLocation regionId) {
        return RegionContentRegistry.getInstance().hasRegisteredBiomes(regionId);
    }

    /**
     * Returns an unmodifiable set of structure IDs explicitly registered for {@code regionId}.
     *
     * @param regionId The region identifier to inspect.
     * @return Unmodifiable set of structure identifiers (empty if none declared).
     */
    public Set<ResourceLocation> getRegisteredRegionStructures(ResourceLocation regionId) {
        return RegionContentRegistry.getInstance().getRegisteredStructures(regionId);
    }

    /**
     * Returns an unmodifiable set of structure {@link TagKey} entries explicitly registered for {@code regionId}.
     *
     * @param regionId The region identifier to inspect.
     * @return Unmodifiable set of structure tags (empty if none declared).
     */
    public Set<TagKey<Structure>> getRegisteredRegionStructureTags(ResourceLocation regionId) {
        return RegionContentRegistry.getInstance().getRegisteredStructureTags(regionId);
    }

    /**
     * Returns {@code true} if {@code regionId} has any explicitly registered structures or structure tags.
     */
    public boolean hasRegisteredRegionStructures(ResourceLocation regionId) {
        return RegionContentRegistry.getInstance().hasRegisteredStructures(regionId);
    }

    /**
     * Checks whether {@code biomeId} is directly registered for {@code regionId} or declared in its {@link com.regionalapi.core.region.BiomeMaskRule}.
     */
    public boolean isBiomeRegisteredForRegion(ResourceLocation regionId, ResourceLocation biomeId) {
        return RegionContentRegistry.getInstance().isBiomeRegistered(regionId, biomeId);
    }

    /**
     * Tag-aware check of whether {@code biomeHolder} matches any biome ID, biome tag,
     * or {@link com.regionalapi.core.region.BiomeMaskRule} registered for {@code regionId}.
     */
    public boolean isBiomeRegisteredForRegion(ResourceLocation regionId, Holder<Biome> biomeHolder) {
        return RegionContentRegistry.getInstance().isBiomeRegistered(regionId, biomeHolder);
    }

    /**
     * Checks whether {@code structureId} is directly registered for {@code regionId}.
     */
    public boolean isStructureRegisteredForRegion(ResourceLocation regionId, ResourceLocation structureId) {
        return RegionContentRegistry.getInstance().isStructureRegistered(regionId, structureId);
    }

    /**
     * Tag-aware check of whether {@code structureHolder} matches any structure ID or structure tag
     * registered for {@code regionId}.
     */
    public boolean isStructureRegisteredForRegion(ResourceLocation regionId, Holder<Structure> structureHolder) {
        return RegionContentRegistry.getInstance().isStructureRegistered(regionId, structureHolder);
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
     * Returns the active Voronoi grid cell shift (between 7 and 13).
     */
    public int getVoronoiCellShift() {
        return VoronoiEngine2D.getActiveCellShift();
    }

    /**
     * Returns the active Voronoi grid cell size in QuartPos units ({@code 1 << cellShift}).
     */
    public int getVoronoiCellSizeQuarts() {
        return VoronoiEngine2D.getActiveCellSizeQuarts();
    }

    /**
     * Returns the active Voronoi grid cell size in block units ({@code (1 << cellShift) * 4}).
     */
    public int getVoronoiCellSizeBlocks() {
        return VoronoiEngine2D.getActiveCellSizeQuarts() << 2;
    }

    /**
     * Sets an explicit Voronoi cell shift (range 7–13) or 0 to enable auto-computation from registered region weights.
     *
     * @param shift Cell shift power-of-two (7–13), or 0 for auto.
     */
    public void setVoronoiCellShift(int shift) {
        RegionRegistry.getInstance().setVoronoiCellShift(shift);
    }

    /**
     * Checks if Voronoi cell shift is auto-computed based on maximum registered region weight.
     */
    public boolean isAutoComputeCellShift() {
        return RegionRegistry.getInstance().isAutoComputeCellShift();
    }

    /**
     * Resolves the maximum weight in blocks across all registered non-fallback regions.
     */
    public int getMaxRegisteredRegionWeight() {
        return RegionRegistry.getInstance().getMaxRegisteredRegionWeight();
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
                short id = data.regional$getSingleRegionId();
                this.serverHomogeneousChunkCache.put(chunkKey, id);
                return id;
            }
        } else {
            ChunkAccess loadedChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (loadedChunk instanceof IRegionChunkData data && data.regional$isInitialized()) {
                return data.regional$getSingleRegionId();
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
