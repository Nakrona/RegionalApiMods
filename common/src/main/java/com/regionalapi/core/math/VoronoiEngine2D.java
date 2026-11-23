package com.regionalapi.core.math;

import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;

/**
 * High-performance 2D Laguerre-Voronoi (Power Diagram) spatial evaluation engine.
 * <p>
 * Evaluates strictly in 2D horizontal space {@code (X, Z)} quantized to canonical 16x16 chunk centers
 * ({@code (chunkX << 2) + 2, (chunkZ << 2) + 2} in {@code QuartPos} coordinates), guaranteeing that
 * every 16x16 chunk contains at most 1 homogeneous region.
 * Never performs {@link Math#sqrt(double)} or floating-point division on hot paths.
 * Dominance is resolved via Squared Power Distance:
 * <pre>
 * Power(P, Si) = (x - xi)^2 + (z - zi)^2 - wi^2
 * </pre>
 * Voronoi cell grid scaling is dynamically adjustable or auto-computed from maximum region weight,
 * enabling macro-scale repeating territories (e.g., 10,000+ blocks per region) without borders swapping rapidly.
 */
public final class VoronoiEngine2D {

    public static final int MIN_CELL_SHIFT = 7;
    public static final int MAX_CELL_SHIFT = 13;

    /**
     * Default grid spacing shift in QuartPos units (2^7 = 128 QuartPos = 512 blocks per Voronoi cell).
     */
    public static final int DEFAULT_CELL_SHIFT = 7;
    public static final int DEFAULT_CELL_SIZE = 1 << DEFAULT_CELL_SHIFT;
    /**
     * Jitter mask within a cell (2^6 - 1 = 63 QuartPos jitter range, keeping seed points well inside cells).
     */
    public static final int DEFAULT_JITTER_MASK = (DEFAULT_CELL_SIZE >> 1) - 1;
    public static final int DEFAULT_JITTER_OFFSET = DEFAULT_CELL_SIZE >> 2;

    private static volatile int activeCellShift = DEFAULT_CELL_SHIFT;
    private static volatile int activeCellSizeQuarts = DEFAULT_CELL_SIZE;
    private static volatile int activeJitterMask = DEFAULT_JITTER_MASK;
    private static volatile int activeJitterOffset = DEFAULT_JITTER_OFFSET;

    private VoronoiEngine2D() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Defines the world-level spatial tessellation behavior of RegionalAPI regions.
     */
    public enum GenerationMode {
        /**
         * Regions tessellate infinitely across the horizontal plane according to weighted Voronoi seed points.
         */
        INFINITE_TESSELLATION,

        /**
         * Locks defined regions within a finite radius around the world origin {@code (X: 0, Z: 0)}
         * and wraps all exterior coordinates in {@link RegionRegistry#FALLBACK_WILDS_ID}.
         */
        FINITE_CENTRALIZED
    }

    /**
     * Returns the current active Voronoi cell shift.
     */
    public static int getActiveCellShift() {
        return activeCellShift;
    }

    /**
     * Returns the current active Voronoi cell size in QuartPos units ({@code 1 << activeCellShift}).
     */
    public static int getActiveCellSizeQuarts() {
        return activeCellSizeQuarts;
    }

    /**
     * Returns the active jitter mask ({@code (activeCellSizeQuarts >> 1) - 1}).
     */
    public static int getActiveJitterMask() {
        return activeJitterMask;
    }

    /**
     * Returns the active jitter quarter offset ({@code activeCellSizeQuarts >> 2}).
     */
    public static int getActiveJitterOffset() {
        return activeJitterOffset;
    }

    /**
     * Sets the active Voronoi grid cell shift, updating cell size, jitter mask, and jitter offset.
     *
     * @param shift Cell shift power-of-two (clamped between {@link #MIN_CELL_SHIFT} and {@link #MAX_CELL_SHIFT}).
     */
    public static void setActiveCellShift(int shift) {
        int clamped = Math.clamp(shift, MIN_CELL_SHIFT, MAX_CELL_SHIFT);
        if (activeCellShift != clamped) {
            activeCellShift = clamped;
            activeCellSizeQuarts = 1 << clamped;
            activeJitterMask = (activeCellSizeQuarts >> 1) - 1;
            activeJitterOffset = activeCellSizeQuarts >> 2;
            com.regionalapi.core.cache.ThreadLocalRegionCache.invalidateAll();
            com.regionalapi.api.RegionalApi.getInstance().clearServerCache();
        }
    }

    /**
     * Computes the recommended Voronoi cell shift from a target radius/weight in block units.
     *
     * @param targetRadiusBlocks Target region radius/weight in blocks (e.g. 10000).
     * @return Log2 cell shift in {@code [7, 13]}.
     */
    public static int computeCellShiftFromWeight(int targetRadiusBlocks) {
        if (targetRadiusBlocks <= 0) {
            return DEFAULT_CELL_SHIFT;
        }
        int quartRadius = Math.max(32, targetRadiusBlocks >> 2);
        int cellShift = 31 - Integer.numberOfLeadingZeros(quartRadius);
        return Math.clamp(cellShift, MIN_CELL_SHIFT, MAX_CELL_SHIFT);
    }

    /**
     * Evaluates the dominant region raw {@code short} ID at the given {@code QuartPos} coordinates.
     * <p>
     * Quantizes horizontal evaluation to the canonical center {@code QuartPos} of the containing
     * 16x16 chunk ({@code (quartX & ~3) + 2, (quartZ & ~3) + 2}), guaranteeing that every chunk
     * resolves to at most 1 homogeneous region across all 16x16 blocks.
     *
     * @param worldSeed         The 64-bit world seed.
     * @param quartX            Horizontal X coordinate in QuartPos units ({@code blockX >> 2}).
     * @param quartZ            Horizontal Z coordinate in QuartPos units ({@code blockZ >> 2}).
     * @param mode              The active {@link GenerationMode}.
     * @param maxFiniteRadiusSq Maximum finite radius squared in QuartPos units (used when {@code mode == FINITE_CENTRALIZED}).
     * @return The winning primitive {@code short} region ID for the chunk.
     */
    public static short evaluateRegionAtQuart(
            long worldSeed,
            int quartX,
            int quartZ,
            GenerationMode mode,
            long maxFiniteRadiusSq
    ) {
        int sampleQuartX = (quartX & ~3) + 2;
        int sampleQuartZ = (quartZ & ~3) + 2;

        long distSq = (long) sampleQuartX * sampleQuartX + (long) sampleQuartZ * sampleQuartZ;
        if (mode == GenerationMode.FINITE_CENTRALIZED && distSq > maxFiniteRadiusSq) {
            return RegionRegistry.FALLBACK_WILDS_ID;
        }

        RegionRegistry registry = RegionRegistry.getInstance();
        if (!registry.isFrozen() || registry.getEvaluatedRegionCount() == 0) {
            return RegionRegistry.FALLBACK_WILDS_ID;
        }

        int cellShift = activeCellShift;
        int cellSizeQuarts = activeCellSizeQuarts;
        int jitterMask = activeJitterMask;
        int jitterOffset = activeJitterOffset;

        int centerGridX = sampleQuartX >> cellShift;
        int centerGridZ = sampleQuartZ >> cellShift;

        long minPowerDistance = Long.MAX_VALUE;
        short winningRegionId = RegionRegistry.FALLBACK_WILDS_ID;
        long maxBiasSq = (long) (cellSizeQuarts >> 2) * (cellSizeQuarts >> 2);

        // Evaluate 3x3 neighborhood of Voronoi cells in 2D horizontal space
        for (int dz = -1; dz <= 1; dz++) {
            int gx = centerGridX - 1;
            int gz = centerGridZ + dz;

            // Unroll inner X loop (-1, 0, +1) for pipeline efficiency
            for (int dx = -1; dx <= 1; dx++, gx++) {
                long cellHash = DeterministicHash.hashGridCell(worldSeed, gx, gz);
                short candidateRegionId = registry.selectRegionForCell(cellHash, gx, gz);
                RegionDefinition definition = registry.getByRawId(candidateRegionId);
                if (definition == null) {
                    continue;
                }

                int cellBaseX = (gx << cellShift) + (cellSizeQuarts >> 1);
                int cellBaseZ = (gz << cellShift) + (cellSizeQuarts >> 1);
                int seedQuartX = (cellBaseX - jitterOffset) + DeterministicHash.extractJitterX(cellHash, jitterMask);
                int seedQuartZ = (cellBaseZ - jitterOffset) + DeterministicHash.extractJitterZ(cellHash, jitterMask);

                long deltaX = (long) sampleQuartX - seedQuartX;
                long deltaZ = (long) sampleQuartZ - seedQuartZ;
                long effectiveWeightSq = Math.min(definition.weightSquaredQuart(), maxBiasSq);
                long powerDist = (deltaX * deltaX) + (deltaZ * deltaZ) - effectiveWeightSq;

                if (powerDist < minPowerDistance) {
                    minPowerDistance = powerDist;
                    winningRegionId = candidateRegionId;
                }
            }
        }

        // Check registered unique regions (isUnique = true) pinned to deterministic coordinates
        short uniqueWinner = registry.evaluateUniqueOverride(sampleQuartX, sampleQuartZ, minPowerDistance);
        if (uniqueWinner != -1) {
            return uniqueWinner;
        }

        return winningRegionId;
    }

    /**
     * Evaluates the authoritative region for an entire 16x16 chunk at its canonical center {@code QuartPos}
     * ({@code (chunkX << 2) + 2, (chunkZ << 2) + 2}), guaranteeing strict 1-chunk = 1-region homogeneity.
     *
     * @param worldSeed         The 64-bit world seed.
     * @param chunkX            The chunk X coordinate.
     * @param chunkZ            The chunk Z coordinate.
     * @param mode              The active {@link GenerationMode}.
     * @param maxFiniteRadiusSq Maximum finite radius squared in QuartPos units.
     * @param outQuartGrid      Pre-allocated 16-element {@code short[]} buffer to populate in-place.
     * @return Always {@code true} as every chunk resolves to a single homogeneous {@code regionId}.
     */
    public static boolean evaluateChunkRegions(
            long worldSeed,
            int chunkX,
            int chunkZ,
            GenerationMode mode,
            long maxFiniteRadiusSq,
            short[] outQuartGrid
    ) {
        int centerQuartX = (chunkX << 2) + 2;
        int centerQuartZ = (chunkZ << 2) + 2;
        short uniformRegionId = evaluateRegionAtQuart(
                worldSeed,
                centerQuartX,
                centerQuartZ,
                mode,
                maxFiniteRadiusSq
        );
        for (int i = 0; i < 16; i++) {
            outQuartGrid[i] = uniformRegionId;
        }
        return true;
    }
}
