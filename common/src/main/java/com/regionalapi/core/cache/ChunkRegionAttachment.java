package com.regionalapi.core.cache;

import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Arrays;

/**
 * High-performance chunk region accessor and backward-compatible helper.
 * <p>
 * Backed by the Zero-GC {@link IRegionChunkData} Duck Interface injected directly into {@link ChunkAccess}.
 */
public final class ChunkRegionAttachment {

    private ChunkRegionAttachment() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Retrieves or lazily populates the {@link IRegionChunkData} for a given {@link ChunkAccess}
     * using the current thread's {@link ThreadLocalRegionCache}.
     *
     * @param chunk The target chunk.
     * @return The populated {@link IRegionChunkData}.
     */
    public static IRegionChunkData getOrPopulate(ChunkAccess chunk) {
        if (chunk instanceof IRegionChunkData data) {
            if (data.regional$isInitialized()) {
                return data;
            }

            ThreadLocalRegionCache cache = ThreadLocalRegionCache.get();
            int slot = cache.ensureChunkSlot(chunk.getPos().x, chunk.getPos().z);
            boolean homogeneous = cache.isSlotHomogeneous(slot);
            if (homogeneous) {
                data.regional$setHomogeneous(cache.getSlotFirstRegionId(slot));
            } else {
                data.regional$setBorder(cache.getSlotQuartGrid(slot));
            }
            return data;
        }

        return FallbackRegionChunkData.INSTANCE;
    }

    /**
     * Fallback immutable implementation used only if a chunk does not implement {@link IRegionChunkData}.
     */
    private static final class FallbackRegionChunkData implements IRegionChunkData {
        private static final FallbackRegionChunkData INSTANCE = new FallbackRegionChunkData();

        @Override
        public boolean regional$isInitialized() {
            return true;
        }

        @Override
        public boolean regional$isHomogeneous() {
            return true;
        }

        @Override
        public short regional$getSingleRegionId() {
            return RegionRegistry.FALLBACK_WILDS_ID;
        }

        @Override
        public short[] regional$getBorderQuartGrid() {
            return null;
        }

        @Override
        public short regional$getRegionAtQuart(int qx, int qz) {
            return RegionRegistry.FALLBACK_WILDS_ID;
        }

        @Override
        public void regional$setHomogeneous(short regionId) {}

        @Override
        public void regional$setBorder(short[] quartGrid16) {}
    }
}
