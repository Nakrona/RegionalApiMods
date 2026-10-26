package com.regionalapi.core.math;

/**
 * Pure, stateless, zero-allocation 64-bit bit-mixing and coordinate hashing engine.
 * <p>
 * Strictly avoids instantiating {@link java.util.Random} or {@code RandomSource}
 * during spatial queries. All operations execute directly in CPU registers using
 * Stafford Variant 13 and XXHash64 avalanche mixers.
 */
public final class DeterministicHash {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long PRIME_X = 0xBF58476D1CE4E5B9L;
    private static final long PRIME_Z = 0x94D049BB133111EBL;
    private static final long SUB_SEED_X = 0x517CC1B727220A95L;
    private static final long SUB_SEED_Z = 0x85EBCA6B2F794C33L;
    private static final long SUB_SEED_REGION = 0xC2B2AE3D27D4EB4FL;

    private DeterministicHash() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Applies the Stafford Variant 13 64-bit mixing function (used in SplitMix64).
     *
     * @param z The raw 64-bit input state.
     * @return A thoroughly avalanched 64-bit value.
     */
    public static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * PRIME_X;
        z = (z ^ (z >>> 27)) * PRIME_Z;
        return z ^ (z >>> 31);
    }

    /**
     * Deterministically hashes a 2D Voronoi grid cell coordinate with a world seed.
     *
     * @param worldSeed The 64-bit world or regional seed.
     * @param gridX     The Voronoi cell X coordinate.
     * @param gridZ     The Voronoi cell Z coordinate.
     * @return A deterministic 64-bit hash for the specified cell.
     */
    public static long hashGridCell(long worldSeed, int gridX, int gridZ) {
        long h = worldSeed + GOLDEN_GAMMA;
        h ^= (long) gridX * PRIME_X;
        h = Long.rotateLeft(h, 27) * GOLDEN_GAMMA;
        h ^= (long) gridZ * PRIME_Z;
        return mixStafford13(h);
    }

    /**
     * Derives the X jitter offset within a grid cell of a power-of-two jitter range.
     *
     * @param cellHash   The base hash produced by {@link #hashGridCell(long, int, int)}.
     * @param jitterMask Bitwise mask {@code (jitterRange - 1)} where jitterRange is a power of two.
     * @return A deterministic integer offset in {@code [0, jitterMask]}.
     */
    public static int extractJitterX(long cellHash, int jitterMask) {
        return (int) (mixStafford13(cellHash + SUB_SEED_X) & jitterMask);
    }

    /**
     * Derives the Z jitter offset within a grid cell of a power-of-two jitter range.
     *
     * @param cellHash   The base hash produced by {@link #hashGridCell(long, int, int)}.
     * @param jitterMask Bitwise mask {@code (jitterRange - 1)} where jitterRange is a power of two.
     * @return A deterministic integer offset in {@code [0, jitterMask]}.
     */
    public static int extractJitterZ(long cellHash, int jitterMask) {
        return (int) (mixStafford13(cellHash + SUB_SEED_Z) & jitterMask);
    }

    /**
     * Derives a non-negative 31-bit deterministic integer for weighted region selection
     * without division where possible, or single modulo against total weight table length.
     *
     * @param cellHash The base hash produced by {@link #hashGridCell(long, int, int)}.
     * @return A deterministic positive 31-bit integer in {@code [0, Integer.MAX_VALUE]}.
     */
    public static int extractRegionSelector(long cellHash) {
        return (int) (mixStafford13(cellHash + SUB_SEED_REGION) >>> 33);
    }

    /**
     * Fast 64-bit chunk key mixer for direct-mapped ring buffer indexing.
     *
     * @param chunkKey The packed {@code ChunkPos.asLong(chunkX, chunkZ)} value.
     * @return A mixed 32-bit hash suitable for bitwise masking.
     */
    public static int mixChunkKey(long chunkKey) {
        long h = chunkKey * GOLDEN_GAMMA;
        h ^= h >>> 32;
        return (int) (h ^ (h >>> 16));
    }
}
