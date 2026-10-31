package com.regionalapi.core.cache;

import com.regionalapi.core.math.DeterministicHash;
import com.regionalapi.core.math.VoronoiEngine2D;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;

/**
 * Lock-free, zero-allocation thread-local direct-mapped ring buffer for worldgen threads.
 * <p>
 * {@code BiomeSource.getNoiseBiome(qx, qy, qz, sampler)} is invoked 1,536 times per chunk
 * concurrently across worker threads without a {@code ChunkAccess} reference. This cache
 * stores 32 chunks per thread with pre-allocated {@code short[32][16]} horizontal grids,
 * eliminating vertical Voronoi recalculation and guaranteeing Zero-GC pressure.
 */
public final class ThreadLocalRegionCache {

    private static final int CACHE_SIZE = 32; // Power of 2
    private static final int MASK = CACHE_SIZE - 1;

    private static final ThreadLocal<ThreadLocalRegionCache> THREAD_CACHE =
            ThreadLocal.withInitial(ThreadLocalRegionCache::new);

    /**
     * Global volatile active world seed updated on server start for lock-free worker access.
     */
    private static volatile long activeWorldSeed = 0L;

    private final long[] keys = new long[CACHE_SIZE]; // ChunkPos.asLong(cx, cz), initialized to Long.MIN_VALUE
    private final short[][] quartGrids = new short[CACHE_SIZE][16]; // Pre-allocated 4x4 horizontal QuartPos grid per chunk
    private final boolean[] homogeneousFlags = new boolean[CACHE_SIZE];
    private long cachedSeedSnapshot = Long.MIN_VALUE;

    public ThreadLocalRegionCache() {
        Arrays.fill(this.keys, Long.MIN_VALUE);
    }

    /**
     * Sets the active world seed for all worldgen worker threads and invalidates stale slots lazily.
     *
     * @param worldSeed The 64-bit world seed.
     */
    public static void setActiveWorldSeed(long worldSeed) {
        activeWorldSeed = worldSeed;
    }

    public static long getActiveWorldSeed() {
        return activeWorldSeed;
    }

    /**
     * Returns the current thread's dedicated {@link ThreadLocalRegionCache} instance.
     */
    public static ThreadLocalRegionCache get() {
        ThreadLocalRegionCache cache = THREAD_CACHE.get();
        long seed = activeWorldSeed;
        if (cache.cachedSeedSnapshot != seed) {
            Arrays.fill(cache.keys, Long.MIN_VALUE);
            cache.cachedSeedSnapshot = seed;
        }
        return cache;
    }

    /**
     * Resolves the primitive {@code short} region ID at {@code (qx, qz)} in QuartPos coordinates.
     * <p>
     * On cache hit, performs an O(1) fast-path check on {@code homogeneousFlags[slot]} or bitwise
     * lookup into {@code quartGrids[slot][((qz & 3) << 2) | (qx & 3)]}.
     * On cache miss, populates {@code quartGrids[slot]} in-place with zero heap allocations.
     *
     * @param qx Horizontal QuartPos X ({@code blockX >> 2}).
     * @param qz Horizontal QuartPos Z ({@code blockZ >> 2}).
     * @return The primitive {@code short} region ID.
     */
    public short getRegionAtQuart(int qx, int qz) {
        int chunkX = qx >> 2;
        int chunkZ = qz >> 2;
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        int slot = DeterministicHash.mixChunkKey(chunkKey) & MASK;

        if (this.keys[slot] != chunkKey) {
            populateSlot(slot, chunkX, chunkZ, chunkKey);
        }

        if (this.homogeneousFlags[slot]) {
            return this.quartGrids[slot][0];
        }

        int columnIndex = ((qz & 3) << 2) | (qx & 3);
        return this.quartGrids[slot][columnIndex];
    }

    /**
     * Ensures the specified chunk is loaded in the thread-local ring buffer and returns its slot index.
     *
     * @param chunkX The chunk X coordinate.
     * @param chunkZ The chunk Z coordinate.
     * @return The ring buffer slot index {@code [0, 31]} holding the chunk's region data.
     */
    public int ensureChunkSlot(int chunkX, int chunkZ) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        int slot = DeterministicHash.mixChunkKey(chunkKey) & MASK;
        if (this.keys[slot] != chunkKey) {
            populateSlot(slot, chunkX, chunkZ, chunkKey);
        }
        return slot;
    }

    public boolean isSlotHomogeneous(int slot) {
        return this.homogeneousFlags[slot];
    }

    public short getSlotFirstRegionId(int slot) {
        return this.quartGrids[slot][0];
    }

    public short[] getSlotQuartGrid(int slot) {
        return this.quartGrids[slot];
    }

    private void populateSlot(int slot, int chunkX, int chunkZ, long chunkKey) {
        RegionRegistry registry = RegionRegistry.getInstance();
        short[] gridBuffer = this.quartGrids[slot];
        boolean homogeneous = VoronoiEngine2D.evaluateChunkRegions(
                activeWorldSeed,
                chunkX,
                chunkZ,
                registry.getGenerationMode(),
                registry.getMaxFiniteRadiusSqQuart(),
                gridBuffer
        );
        this.homogeneousFlags[slot] = homogeneous;
        this.keys[slot] = chunkKey;
    }
}
