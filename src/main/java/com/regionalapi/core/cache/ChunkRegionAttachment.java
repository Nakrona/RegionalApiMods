package com.regionalapi.core.cache;

import com.regionalapi.RegionalMod;
import com.regionalapi.core.region.RegionRegistry;
import com.mojang.serialization.Codec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Persistent NeoForge Data Attachment for chunk-level regional data and player last-known region state.
 * <p>
 * Implements compact binary serialization via {@link IAttachmentSerializer}:
 * <ul>
 *     <li>If {@code isHomogeneous == true}: serializes exactly <b>3 bytes</b> (1 boolean byte + 1 big-endian {@code short}).</li>
 *     <li>If {@code isHomogeneous == false} (Border Chunk): serializes <b>33 bytes</b> (1 boolean byte + 16 big-endian {@code short}s representing the 4x4 QuartPos grid).</li>
 * </ul>
 */
public final class ChunkRegionAttachment {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, RegionalMod.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ChunkRegionData>> CHUNK_REGION_DATA =
            ATTACHMENT_TYPES.register("chunk_region_data", () -> AttachmentType.builder(ChunkRegionData::createDefault)
                    .serialize(new ChunkRegionDataSerializer())
                    .build());

    /**
     * Lightweight primitive-backed attachment tracking a player's current region ID and whether
     * they currently reside in a border chunk requiring per-tick QuartPos boundary polling.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerRegionTracker>> PLAYER_REGION_TRACKER =
            ATTACHMENT_TYPES.register("player_region_tracker", () -> AttachmentType.builder(() -> new PlayerRegionTracker())
                    .serialize(Codec.SHORT.xmap(PlayerRegionTracker::new, PlayerRegionTracker::getLastRegionId))
                    .copyOnDeath()
                    .build());

    private ChunkRegionAttachment() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Retrieves or populates the {@link ChunkRegionData} attachment for a given {@link ChunkAccess}
     * using the current thread's {@link ThreadLocalRegionCache}.
     *
     * @param chunk The target chunk.
     * @return The initialized {@link ChunkRegionData}.
     */
    public static ChunkRegionData getOrPopulate(ChunkAccess chunk) {
        if (chunk.hasData(CHUNK_REGION_DATA)) {
            ChunkRegionData existing = chunk.getData(CHUNK_REGION_DATA);
            if (existing != null && existing.isInitialized()) {
                return existing;
            }
        }

        ThreadLocalRegionCache cache = ThreadLocalRegionCache.get();
        int slot = cache.ensureChunkSlot(chunk.getPos().x, chunk.getPos().z);
        boolean homogeneous = cache.isSlotHomogeneous(slot);
        short[] grid = cache.getSlotQuartGrid(slot);

        ChunkRegionData data;
        if (homogeneous) {
            data = ChunkRegionData.ofHomogeneous(grid[0]);
        } else {
            data = ChunkRegionData.ofBorder(grid);
        }
        chunk.setData(CHUNK_REGION_DATA.get(), data);
        return data;
    }

    /**
     * Compact in-memory representation of a chunk's 2D region distribution.
     */
    public static final class ChunkRegionData {
        private boolean initialized;
        private boolean homogeneous;
        private short singleRegionId;
        private short[] borderQuartGrid; // Only allocated on border chunks (16 shorts)

        private ChunkRegionData(boolean initialized, boolean homogeneous, short singleRegionId, short[] borderQuartGrid) {
            this.initialized = initialized;
            this.homogeneous = homogeneous;
            this.singleRegionId = singleRegionId;
            this.borderQuartGrid = borderQuartGrid;
        }

        public static ChunkRegionData createDefault() {
            return new ChunkRegionData(false, true, RegionRegistry.FALLBACK_WILDS_ID, null);
        }

        public static ChunkRegionData ofHomogeneous(short regionId) {
            return new ChunkRegionData(true, true, regionId, null);
        }

        public static ChunkRegionData ofBorder(short[] sourceGrid16) {
            short[] copy = Arrays.copyOf(sourceGrid16, 16);
            return new ChunkRegionData(true, false, copy[0], copy);
        }

        public boolean isInitialized() {
            return this.initialized;
        }

        public boolean isHomogeneous() {
            return this.homogeneous;
        }

        public short getSingleRegionId() {
            return this.singleRegionId;
        }

        /**
         * Resolves the region ID at the given horizontal QuartPos coordinates {@code (qx, qz)} in O(1) time.
         */
        public short getRegionAtQuart(int qx, int qz) {
            if (this.homogeneous || this.borderQuartGrid == null) {
                return this.singleRegionId;
            }
            int columnIndex = ((qz & 3) << 2) | (qx & 3);
            return this.borderQuartGrid[columnIndex];
        }

        public short[] getBorderQuartGridRaw() {
            return this.borderQuartGrid;
        }
    }

    /**
     * Compact binary serializer writing 3 bytes for homogeneous chunks and 33 bytes for border chunks.
     */
    public static final class ChunkRegionDataSerializer implements IAttachmentSerializer<ByteArrayTag, ChunkRegionData> {

        @Override
        public ChunkRegionData read(IAttachmentHolder holder, ByteArrayTag tag, HolderLookup.Provider provider) {
            byte[] bytes = tag.getAsByteArray();
            if (bytes.length == 3 && bytes[0] == 1) {
                short regionId = (short) (((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF));
                return ChunkRegionData.ofHomogeneous(regionId);
            } else if (bytes.length == 33 && bytes[0] == 0) {
                short[] grid = new short[16];
                ByteBuffer buf = ByteBuffer.wrap(bytes, 1, 32);
                for (int i = 0; i < 16; i++) {
                    grid[i] = buf.getShort();
                }
                return ChunkRegionData.ofBorder(grid);
            }
            return ChunkRegionData.createDefault();
        }

        @Override
        public ByteArrayTag write(ChunkRegionData attachment, HolderLookup.Provider provider) {
            if (!attachment.isInitialized()) {
                return null;
            }
            if (attachment.isHomogeneous()) {
                byte[] bytes = new byte[3];
                bytes[0] = 1;
                short id = attachment.getSingleRegionId();
                bytes[1] = (byte) ((id >>> 8) & 0xFF);
                bytes[2] = (byte) (id & 0xFF);
                return new ByteArrayTag(bytes);
            } else {
                byte[] bytes = new byte[33];
                bytes[0] = 0;
                ByteBuffer buf = ByteBuffer.wrap(bytes, 1, 32);
                short[] grid = attachment.getBorderQuartGridRaw();
                for (int i = 0; i < 16; i++) {
                    buf.putShort(grid != null ? grid[i] : attachment.getSingleRegionId());
                }
                return new ByteArrayTag(bytes);
            }
        }
    }

    /**
     * Tracks a player's last known primitive {@code short} region ID and whether their current chunk
     * is a border chunk (`isHomogeneous == false`) requiring QuartPos boundary polling.
     */
    public static final class PlayerRegionTracker {
        private short lastRegionId;
        private boolean inBorderChunk;
        private int lastQuartX = Integer.MIN_VALUE;
        private int lastQuartZ = Integer.MIN_VALUE;

        public PlayerRegionTracker() {
            this.lastRegionId = -1;
            this.inBorderChunk = false;
        }

        public PlayerRegionTracker(short lastRegionId) {
            this.lastRegionId = lastRegionId;
            this.inBorderChunk = false;
        }

        public short getLastRegionId() {
            return this.lastRegionId;
        }

        public void setLastRegionId(short lastRegionId) {
            this.lastRegionId = lastRegionId;
        }

        public boolean isInBorderChunk() {
            return this.inBorderChunk;
        }

        public void setInBorderChunk(boolean inBorderChunk) {
            this.inBorderChunk = inBorderChunk;
        }

        public int getLastQuartX() {
            return this.lastQuartX;
        }

        public int getLastQuartZ() {
            return this.lastQuartZ;
        }

        public void updateLastQuartPos(int qx, int qz) {
            this.lastQuartX = qx;
            this.lastQuartZ = qz;
        }
    }
}
