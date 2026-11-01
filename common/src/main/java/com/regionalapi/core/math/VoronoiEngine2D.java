package com.regionalapi.core.math;

import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;

/**
 * High-performance 2D Laguerre-Voronoi (Power Diagram) spatial evaluation engine.
 * <p>
 * Evaluates strictly in 2D horizontal space {@code (X, Z)} at {@code QuartPos} resolution.
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
     *
     * @param worldSeed         The 64-bit world seed.
     * @param quartX            Horizontal X coordinate in QuartPos units ({@code blockX >> 2}).
     * @param quartZ            Horizontal Z coordinate in QuartPos units ({@code blockZ >> 2}).
     * @param mode              The active {@link GenerationMode}.
     * @param maxFiniteRadiusSq Maximum finite radius squared in QuartPos units (used when {@code mode == FINITE_CENTRALIZED}).
     * @return The winning primitive {@code short} region ID.
     */
    public static short evaluateRegionAtQuart(
            long worldSeed,
            int quartX,
            int quartZ,
            GenerationMode mode,
            long maxFiniteRadiusSq
    ) {
        long distSq = (long) quartX * quartX + (long) quartZ * quartZ;
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

        int centerGridX = quartX >> cellShift;
        int centerGridZ = quartZ >> cellShift;

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

                long deltaX = (long) quartX - seedQuartX;
                long deltaZ = (long) quartZ - seedQuartZ;
                long effectiveWeightSq = Math.min(definition.weightSquaredQuart(), maxBiasSq);
                long powerDist = (deltaX * deltaX) + (deltaZ * deltaZ) - effectiveWeightSq;

                if (powerDist < minPowerDistance) {
                    minPowerDistance = powerDist;
                    winningRegionId = candidateRegionId;
                }
            }
        }

        // Check registered unique regions (isUnique = true) pinned to deterministic coordinates
        short uniqueWinner = registry.evaluateUniqueOverride(quartX, quartZ, minPowerDistance);
        if (uniqueWinner != -1) {
            return uniqueWinner;
        }

        return winningRegionId;
    }

    /**
     * Evaluates the dominant Voronoi seed cell key (packed gridX, gridZ, and regionId) for homogeneity checks.
     * If all 4 corners of a chunk resolve to the exact same winning Voronoi seed point and no competing cell
     * can geometrically intrude into the convex 4x4 QuartPos square, the chunk is guaranteed homogeneous.
     *
     * @param worldSeed         The 64-bit world seed.
     * @param chunkX            The chunk X coordinate.
     * @param chunkZ            The chunk Z coordinate.
     * @param mode              The active {@link GenerationMode}.
     * @param maxFiniteRadiusSq Maximum finite radius squared in QuartPos units.
     * @param outQuartGrid      Pre-allocated 16-element {@code short[]} buffer to populate in-place.
     * @return {@code true} if the entire chunk resolves to a single homogeneous {@code regionId}; {@code false} if border chunk.
     */
    public static boolean evaluateChunkRegions(
            long worldSeed,
            int chunkX,
            int chunkZ,
            GenerationMode mode,
            long maxFiniteRadiusSq,
            short[] outQuartGrid
    ) {
        int baseQuartX = chunkX << 2;
        int baseQuartZ = chunkZ << 2;
        int maxQuartX = baseQuartX + 3;
        int maxQuartZ = baseQuartZ + 3;

        // Early-exit finite centralized check for entire chunk bounding box
        if (mode == GenerationMode.FINITE_CENTRALIZED) {
            long minAbsX = (baseQuartX > 0) ? baseQuartX : (maxQuartX < 0 ? -maxQuartX : 0);
            long minAbsZ = (baseQuartZ > 0) ? baseQuartZ : (maxQuartZ < 0 ? -maxQuartZ : 0);
            if ((minAbsX * minAbsX + minAbsZ * minAbsZ) > maxFiniteRadiusSq) {
                for (int i = 0; i < 16; i++) {
                    outQuartGrid[i] = RegionRegistry.FALLBACK_WILDS_ID;
                }
                return true;
            }
        }

        // Hierarchical fast-path: evaluate the 4 corners of the chunk and track winning seed identity
        long seed00 = evaluateWinningSeedToken(worldSeed, baseQuartX, baseQuartZ, mode, maxFiniteRadiusSq);
        long seed30 = evaluateWinningSeedToken(worldSeed, maxQuartX, baseQuartZ, mode, maxFiniteRadiusSq);
        if (seed00 == seed30) {
            long seed03 = evaluateWinningSeedToken(worldSeed, baseQuartX, maxQuartZ, mode, maxFiniteRadiusSq);
            if (seed00 == seed03) {
                long seed33 = evaluateWinningSeedToken(worldSeed, maxQuartX, maxQuartZ, mode, maxFiniteRadiusSq);
                if (seed00 == seed33) {
                    // By convexity of Laguerre-Voronoi cells (intersections of half-planes),
                    // if all 4 corners of an axis-aligned rectangle belong to the exact same Voronoi site
                    // and do not cross the finite radius boundary, the entire interior belongs to that site.
                    boolean crossesFiniteBoundary = false;
                    if (mode == GenerationMode.FINITE_CENTRALIZED) {
                        long maxAbsX = Math.max(Math.abs((long) baseQuartX), Math.abs((long) maxQuartX));
                        long maxAbsZ = Math.max(Math.abs((long) baseQuartZ), Math.abs((long) maxQuartZ));
                        long maxCornerDistSq = maxAbsX * maxAbsX + maxAbsZ * maxAbsZ;
                        long minCornerDistSq = Math.min((long) baseQuartX * baseQuartX, (long) maxQuartX * maxQuartX)
                                + Math.min((long) baseQuartZ * baseQuartZ, (long) maxQuartZ * maxQuartZ);
                        crossesFiniteBoundary = (minCornerDistSq <= maxFiniteRadiusSq) != (maxCornerDistSq <= maxFiniteRadiusSq);
                    }

                    if (!crossesFiniteBoundary) {
                        short uniformRegionId = (short) (seed00 & 0xFFFFL);
                        for (int i = 0; i < 16; i++) {
                            outQuartGrid[i] = uniformRegionId;
                        }
                        return true;
                    }
                }
            }
        }

        // Slow path (Border Chunk): evaluate all 16 horizontal QuartPos columns in-place
        short firstRegionId = 0;
        boolean allSameRegion = true;
        for (int qzOffset = 0; qzOffset < 4; qzOffset++) {
            int rowBase = qzOffset << 2;
            int qz = baseQuartZ + qzOffset;
            for (int qxOffset = 0; qxOffset < 4; qxOffset++) {
                int idx = rowBase | qxOffset;
                short regionId = evaluateRegionAtQuart(
                        worldSeed,
                        baseQuartX + qxOffset,
                        qz,
                        mode,
                        maxFiniteRadiusSq
                );
                outQuartGrid[idx] = regionId;
                if (idx == 0) {
                    firstRegionId = regionId;
                } else if (regionId != firstRegionId) {
                    allSameRegion = false;
                }
            }
        }

        return allSameRegion;
    }

    /**
     * Returns a packed 64-bit token identifying both the winning Voronoi site and its regionId.
     * Bits [0..15]: winning {@code short} regionId.
     * Bits [16..63]: hashed site coordinates so distinct sites with the same region or different regions are distinguished.
     */
    private static long evaluateWinningSeedToken(
            long worldSeed,
            int quartX,
            int quartZ,
            GenerationMode mode,
            long maxFiniteRadiusSq
    ) {
        long distSq = (long) quartX * quartX + (long) quartZ * quartZ;
        if (mode == GenerationMode.FINITE_CENTRALIZED && distSq > maxFiniteRadiusSq) {
            return (0x7FFF_FFFF_FFFFL << 16) | (RegionRegistry.FALLBACK_WILDS_ID & 0xFFFFL);
        }

        RegionRegistry registry = RegionRegistry.getInstance();
        if (!registry.isFrozen() || registry.getEvaluatedRegionCount() == 0) {
            return RegionRegistry.FALLBACK_WILDS_ID & 0xFFFFL;
        }

        int cellShift = activeCellShift;
        int cellSizeQuarts = activeCellSizeQuarts;
        int jitterMask = activeJitterMask;
        int jitterOffset = activeJitterOffset;

        int centerGridX = quartX >> cellShift;
        int centerGridZ = quartZ >> cellShift;

        long minPowerDistance = Long.MAX_VALUE;
        short winningRegionId = RegionRegistry.FALLBACK_WILDS_ID;
        long winningSiteHash = 0L;
        long maxBiasSq = (long) (cellSizeQuarts >> 2) * (cellSizeQuarts >> 2);

        for (int dz = -1; dz <= 1; dz++) {
            int gx = centerGridX - 1;
            int gz = centerGridZ + dz;

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

                long deltaX = (long) quartX - seedQuartX;
                long deltaZ = (long) quartZ - seedQuartZ;
                long effectiveWeightSq = Math.min(definition.weightSquaredQuart(), maxBiasSq);
                long powerDist = (deltaX * deltaX) + (deltaZ * deltaZ) - effectiveWeightSq;

                if (powerDist < minPowerDistance) {
                    minPowerDistance = powerDist;
                    winningRegionId = candidateRegionId;
                    winningSiteHash = cellHash;
                }
            }
        }

        long uniqueToken = registry.evaluateUniqueOverrideToken(quartX, quartZ, minPowerDistance);
        if (uniqueToken != -1L) {
            return uniqueToken;
        }

        return (winningSiteHash & 0xFFFF_FFFF_FFFF_0000L) | (winningRegionId & 0xFFFFL);
    }
}
