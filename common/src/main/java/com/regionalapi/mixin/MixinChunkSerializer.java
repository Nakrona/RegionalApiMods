package com.regionalapi.mixin;

import com.regionalapi.core.cache.IRegionChunkData;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionRegistry;
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

/**
 * Persists and deserializes compact binary regional data in chunk NBT via {@link ChunkSerializer}.
 * <p>
 * Every chunk is guaranteed homogeneous (1 chunk = max 1 region) and serialized in 3 bytes
 * (1 byte homogeneous flag + 1 big-endian short region ID). Legacy 33-byte border tags from older
 * saves are automatically upgraded to the authoritative single chunk region upon loading.
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

        byte[] bytes = new byte[3];
        bytes[0] = 1;
        short id = chunkData.regional$getSingleRegionId();
        bytes[1] = (byte) ((id >>> 8) & 0xFF);
        bytes[2] = (byte) (id & 0xFF);
        tag.putByteArray(REGIONAL_DATA_TAG, bytes);
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
        if (!RegionRegistry.getInstance().isFrozen() || ThreadLocalRegionCache.getActiveWorldSeed() == 0L) {
            return;
        }

        if (chunk instanceof IRegionChunkData chunkData) {
            ThreadLocalRegionCache cache = ThreadLocalRegionCache.get();
            int slot = cache.ensureChunkSlot(chunkPos.x, chunkPos.z);
            short expectedRegionId = cache.getSlotFirstRegionId(slot);

            byte[] bytes = compoundTag.getByteArray(REGIONAL_DATA_TAG);
            if (bytes.length == 3 && bytes[0] == 1) {
                short regionId = (short) (((bytes[1] & 0xFF) << 8) | (bytes[2] & 0xFF));
                if (regionId == expectedRegionId) {
                    chunkData.regional$setHomogeneous(regionId);
                    return;
                }
            }

            // If saved NBT is stale or uses legacy 33-byte border format, upgrade to single authoritative chunk region
            chunkData.regional$setHomogeneous(expectedRegionId);
        }
    }
}
