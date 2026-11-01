package com.regionalapi.mixin;

import com.regionalapi.core.cache.IRegionChunkData;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Persists and deserializes compact binary regional data in chunk NBT via {@link ChunkSerializer}.
 * <ul>
 *     <li>Homogeneous chunk: 3 bytes (1 byte flag + 1 big-endian short).</li>
 *     <li>Border chunk: 33 bytes (1 byte flag + 16 big-endian shorts).</li>
 * </ul>
 */
@Mixin(ChunkSerializer.class)
public abstract class MixinChunkSerializer {

    @Unique
    private static final String REGIONAL_DATA_TAG = "regionalapi:region_data";

    @Inject(method = "write", at = @At("RETURN"))
    private static void regional$writeRegionData(
            ServerLevel serverLevel,
            ChunkAccess chunkAccess,
            CallbackInfoReturnable<CompoundTag> cir
    ) {
        CompoundTag tag = cir.getReturnValue();
        if (tag == null || !(chunkAccess instanceof IRegionChunkData chunkData) || !chunkData.regional$isInitialized()) {
            return;
        }

        if (chunkData.regional$isHomogeneous()) {
            byte[] bytes = new byte[3];
            bytes[0] = 1;
            short id = chunkData.regional$getSingleRegionId();
            bytes[1] = (byte) ((id >>> 8) & 0xFF);
            bytes[2] = (byte) (id & 0xFF);
            tag.putByteArray(REGIONAL_DATA_TAG, bytes);
        } else {
            byte[] bytes = new byte[33];
            bytes[0] = 0;
            ByteBuffer buf = ByteBuffer.wrap(bytes, 1, 32);
            short[] grid = chunkData.regional$getBorderQuartGrid();
            for (int i = 0; i < 16; i++) {
                buf.putShort(grid != null ? grid[i] : chunkData.regional$getSingleRegionId());
            }
            tag.putByteArray(REGIONAL_DATA_TAG, bytes);
        }
    }

    @Inject(method = "read", at = @At("RETURN"))
    private static void regional$readRegionData(
            ServerLevel serverLevel,
            PoiManager poiManager,
            RegionStorageInfo regionStorageInfo,
            ChunkPos chunkPos,
            CompoundTag compoundTag,
            CallbackInfoReturnable<ProtoChunk> cir
    ) {
        ProtoChunk chunk = cir.getReturnValue();
        if (chunk == null || compoundTag == null || !compoundTag.contains(REGIONAL_DATA_TAG, Tag.TAG_BYTE_ARRAY)) {
            return;
        }

        if (chunk instanceof IRegionChunkData chunkData) {
            com.regionalapi.core.cache.ThreadLocalRegionCache cache = com.regionalapi.core.cache.ThreadLocalRegionCache.get();
            int slot = cache.ensureChunkSlot(chunkPos.x, chunkPos.z);
            boolean expectedHomogeneous = cache.isSlotHomogeneous(slot);

            byte[] bytes = compoundTag.getByteArray(REGIONAL_DATA_TAG);
            if (expectedHomogeneous && bytes.length == 3 && bytes[0] == 1) {
                short regionId = (short) (((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF));
                if (regionId == cache.getSlotFirstRegionId(slot)) {
                    chunkData.regional$setHomogeneous(regionId);
                    return;
                }
            } else if (!expectedHomogeneous && bytes.length == 33 && bytes[0] == 0) {
                short[] expectedGrid = cache.getSlotQuartGrid(slot);
                short[] grid = new short[16];
                ByteBuffer buf = ByteBuffer.wrap(bytes, 1, 32);
                boolean matches = true;
                for (int i = 0; i < 16; i++) {
                    grid[i] = buf.getShort();
                    if (grid[i] != expectedGrid[i]) {
                        matches = false;
                    }
                }
                if (matches) {
                    chunkData.regional$setBorder(grid);
                    return;
                }
            }

            // If saved NBT is stale (e.g. config, weight, or cellShift changed), populate fresh data immediately
            if (expectedHomogeneous) {
                chunkData.regional$setHomogeneous(cache.getSlotFirstRegionId(slot));
            } else {
                chunkData.regional$setBorder(cache.getSlotQuartGrid(slot));
            }
        }
    }
}
