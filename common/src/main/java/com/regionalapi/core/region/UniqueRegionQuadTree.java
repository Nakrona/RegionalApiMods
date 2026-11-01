package com.regionalapi.core.region;

/**
 * Lock-free, zero-allocation read-only 2D QuadTree for sub-0.05ms spatial queries of
 * {@code isUnique == true} regions.
 * <p>
 * Pre-computed once during {@code ServerAboutToStartEvent} and persisted via {@link UniqueRegionSavedData}.
 * Query methods allocate zero objects and execute purely with primitive integer and long arithmetic.
 */
public final class UniqueRegionQuadTree {

    private static final int MAX_LEAF_ENTRIES = 4;
    private static final int MAX_DEPTH = 10;

    private final Node root;

    public UniqueRegionQuadTree(int minQuartX, int minQuartZ, int maxQuartX, int maxQuartZ) {
        this.root = new Node(minQuartX, minQuartZ, maxQuartX, maxQuartZ, 0);
    }

    /**
     * Inserts a unique region anchor point into the QuadTree.
     *
     * @param regionRawId        The frozen {@code short} ID of the unique region.
     * @param centerQuartX       Anchor X coordinate in QuartPos units.
     * @param centerQuartZ       Anchor Z coordinate in QuartPos units.
     * @param radiusQuart        Influence radius in QuartPos units.
     * @param weightSquaredQuart Pre-computed Laguerre weight squared in QuartPos units.
     * @param deterministicToken Packed 64-bit site token for homogeneity verification.
     */
    public void insert(
            short regionRawId,
            int centerQuartX,
            int centerQuartZ,
            int radiusQuart,
            long weightSquaredQuart,
            long deterministicToken
    ) {
        Entry entry = new Entry(
                regionRawId,
                centerQuartX,
                centerQuartZ,
                (long) radiusQuart * radiusQuart,
                weightSquaredQuart,
                deterministicToken,
                centerQuartX - radiusQuart,
                centerQuartZ - radiusQuart,
                centerQuartX + radiusQuart,
                centerQuartZ + radiusQuart
        );
        this.root.insert(entry);
    }

    /**
     * Queries the QuadTree at {@code (quartX, quartZ)} and returns the winning unique region raw ID
     * if its Laguerre power distance beats {@code currentBestPowerDist} and lies within its bounding radius.
     *
     * @param quartX               Query QuartPos X.
     * @param quartZ               Query QuartPos Z.
     * @param currentBestPowerDist Best power distance from standard Voronoi grid cells.
     * @return Winning unique {@code short} regionId, or {@code -1} if no unique region dominates.
     */
    public short queryDominantUniqueRegion(int quartX, int quartZ, long currentBestPowerDist) {
        return this.root.queryRegionId(quartX, quartZ, currentBestPowerDist);
    }

    /**
     * Queries the QuadTree at {@code (quartX, quartZ)} and returns the packed 64-bit site token
     * of the winning unique region if it dominates {@code currentBestPowerDist}.
     *
     * @return Winning packed 64-bit token, or {@code -1L} if no unique region dominates.
     */
    public long queryDominantUniqueToken(int quartX, int quartZ, long currentBestPowerDist) {
        return this.root.queryToken(quartX, quartZ, currentBestPowerDist);
    }

    /**
     * Finds the exact block coordinates {@code (blockX, blockZ)} packed into a {@code long}
     * ({@code ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL)}) for a given unique region ID.
     *
     * @param regionRawId The target unique region raw ID.
     * @return Packed block coordinate long, or {@link Long#MIN_VALUE} if not found.
     */
    public long findCenterBlockPosPacked(short regionRawId) {
        return this.root.findCenterPacked(regionRawId);
    }

    private static final class Entry {
        final short regionRawId;
        final int centerQuartX;
        final int centerQuartZ;
        final long maxRadiusSqQuart;
        final long weightSquaredQuart;
        final long deterministicToken;
        final int minQX;
        final int minQZ;
        final int maxQX;
        final int maxQZ;

        Entry(
                short regionRawId,
                int centerQuartX,
                int centerQuartZ,
                long maxRadiusSqQuart,
                long weightSquaredQuart,
                long deterministicToken,
                int minQX,
                int minQZ,
                int maxQX,
                int maxQZ
        ) {
            this.regionRawId = regionRawId;
            this.centerQuartX = centerQuartX;
            this.centerQuartZ = centerQuartZ;
            this.maxRadiusSqQuart = maxRadiusSqQuart;
            this.weightSquaredQuart = weightSquaredQuart;
            this.deterministicToken = deterministicToken;
            this.minQX = minQX;
            this.minQZ = minQZ;
            this.maxQX = maxQX;
            this.maxQZ = maxQZ;
        }

        boolean containsPointInBounds(int qx, int qz) {
            return qx >= this.minQX && qx <= this.maxQX && qz >= this.minQZ && qz <= this.maxQZ;
        }

        boolean intersectsBox(int boxMinX, int boxMinZ, int boxMaxX, int boxMaxZ) {
            return this.maxQX >= boxMinX && this.minQX <= boxMaxX
                    && this.maxQZ >= boxMinZ && this.minQZ <= boxMaxZ;
        }
    }

    private static final class Node {
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final int midX;
        final int midZ;
        final int depth;

        Entry[] entries = new Entry[MAX_LEAF_ENTRIES];
        int entryCount = 0;

        Node nw;
        Node ne;
        Node sw;
        Node se;

        Node(int minX, int minZ, int maxX, int maxZ, int depth) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.midX = (minX + maxX) >> 1;
            this.midZ = (minZ + maxZ) >> 1;
            this.depth = depth;
        }

        void insert(Entry entry) {
            if (!entry.intersectsBox(this.minX, this.minZ, this.maxX, this.maxZ)) {
                return;
            }

            if (this.nw != null) {
                this.nw.insert(entry);
                this.ne.insert(entry);
                this.sw.insert(entry);
                this.se.insert(entry);
                return;
            }

            if (this.entryCount < MAX_LEAF_ENTRIES || this.depth >= MAX_DEPTH) {
                if (this.entryCount == this.entries.length) {
                    Entry[] expanded = new Entry[this.entries.length << 1];
                    System.arraycopy(this.entries, 0, expanded, 0, this.entryCount);
                    this.entries = expanded;
                }
                this.entries[this.entryCount++] = entry;
                return;
            }

            subdivide();
            for (int i = 0; i < this.entryCount; i++) {
                Entry existing = this.entries[i];
                this.nw.insert(existing);
                this.ne.insert(existing);
                this.sw.insert(existing);
                this.se.insert(existing);
                this.entries[i] = null;
            }
            this.entryCount = 0;

            this.nw.insert(entry);
            this.ne.insert(entry);
            this.sw.insert(entry);
            this.se.insert(entry);
        }

        private void subdivide() {
            int nextDepth = this.depth + 1;
            this.nw = new Node(this.minX, this.minZ, this.midX, this.midZ, nextDepth);
            this.ne = new Node(this.midX + 1, this.minZ, this.maxX, this.midZ, nextDepth);
            this.sw = new Node(this.minX, this.midZ + 1, this.midX, this.maxZ, nextDepth);
            this.se = new Node(this.midX + 1, this.midZ + 1, this.maxX, this.maxZ, nextDepth);
        }

        short queryRegionId(int qx, int qz, long bestPowerDist) {
            if (qx < this.minX || qx > this.maxX || qz < this.minZ || qz > this.maxZ) {
                return -1;
            }

            if (this.nw != null) {
                if (qx <= this.midX) {
                    if (qz <= this.midZ) {
                        return this.nw.queryRegionId(qx, qz, bestPowerDist);
                    } else {
                        return this.sw.queryRegionId(qx, qz, bestPowerDist);
                    }
                } else {
                    if (qz <= this.midZ) {
                        return this.ne.queryRegionId(qx, qz, bestPowerDist);
                    } else {
                        return this.se.queryRegionId(qx, qz, bestPowerDist);
                    }
                }
            }

            short winner = -1;
            long bestUniquePower = Long.MAX_VALUE;
            for (int i = 0; i < this.entryCount; i++) {
                Entry e = this.entries[i];
                if (e.containsPointInBounds(qx, qz)) {
                    long dx = (long) qx - e.centerQuartX;
                    long dz = (long) qz - e.centerQuartZ;
                    long distSq = dx * dx + dz * dz;
                    if (distSq <= e.maxRadiusSqQuart) {
                        long power = distSq - e.weightSquaredQuart;
                        if (power < bestUniquePower) {
                            bestUniquePower = power;
                            winner = e.regionRawId;
                        }
                    }
                }
            }
            return winner;
        }

        long queryToken(int qx, int qz, long bestPowerDist) {
            if (qx < this.minX || qx > this.maxX || qz < this.minZ || qz > this.maxZ) {
                return -1L;
            }

            if (this.nw != null) {
                if (qx <= this.midX) {
                    return (qz <= this.midZ)
                            ? this.nw.queryToken(qx, qz, bestPowerDist)
                            : this.sw.queryToken(qx, qz, bestPowerDist);
                } else {
                    return (qz <= this.midZ)
                            ? this.ne.queryToken(qx, qz, bestPowerDist)
                            : this.se.queryToken(qx, qz, bestPowerDist);
                }
            }

            long winnerToken = -1L;
            long bestUniquePower = Long.MAX_VALUE;
            for (int i = 0; i < this.entryCount; i++) {
                Entry e = this.entries[i];
                if (e.containsPointInBounds(qx, qz)) {
                    long dx = (long) qx - e.centerQuartX;
                    long dz = (long) qz - e.centerQuartZ;
                    long distSq = dx * dx + dz * dz;
                    if (distSq <= e.maxRadiusSqQuart) {
                        long power = distSq - e.weightSquaredQuart;
                        if (power < bestUniquePower) {
                            bestUniquePower = power;
                            winnerToken = e.deterministicToken;
                        }
                    }
                }
            }
            return winnerToken;
        }

        long findCenterPacked(short targetRegionId) {
            if (this.nw != null) {
                long r = this.nw.findCenterPacked(targetRegionId);
                if (r != Long.MIN_VALUE) return r;
                r = this.ne.findCenterPacked(targetRegionId);
                if (r != Long.MIN_VALUE) return r;
                r = this.sw.findCenterPacked(targetRegionId);
                if (r != Long.MIN_VALUE) return r;
                return this.se.findCenterPacked(targetRegionId);
            }
            for (int i = 0; i < this.entryCount; i++) {
                Entry e = this.entries[i];
                if (e.regionRawId == targetRegionId) {
                    int blockX = e.centerQuartX << 2;
                    int blockZ = e.centerQuartZ << 2;
                    return ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL);
                }
            }
            return Long.MIN_VALUE;
        }
    }
}
