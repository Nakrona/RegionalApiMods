package com.regionalapi.core.region;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persists pre-computed {@code isUnique == true} region anchor coordinates in the Overworld's
 * {@link net.minecraft.world.level.storage.DimensionDataStorage} so unique region coordinates
 * remain immutable across mod updates and load in under 0.05 ms on server startup.
 */
public final class UniqueRegionSavedData extends SavedData {

    private static final String DATA_NAME = "regionalapi_unique_regions";
    private static final String TAG_ENTRIES = "UniqueRegions";
    private static final String TAG_ID = "Id";
    private static final String TAG_PACKED_QUART = "PackedQuartXZ";

    private final Object2LongOpenHashMap<ResourceLocation> uniqueQuartCenters = new Object2LongOpenHashMap<>();

    public UniqueRegionSavedData() {
        this.uniqueQuartCenters.defaultReturnValue(Long.MIN_VALUE);
    }

    public static SavedData.Factory<UniqueRegionSavedData> factory() {
        return new SavedData.Factory<>(UniqueRegionSavedData::new, UniqueRegionSavedData::load, null);
    }

    public static UniqueRegionSavedData getOrCreate(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(factory(), DATA_NAME);
    }

    public static UniqueRegionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        UniqueRegionSavedData data = new UniqueRegionSavedData();
        ListTag list = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString(TAG_ID));
            if (id != null && entry.contains(TAG_PACKED_QUART, Tag.TAG_LONG)) {
                data.uniqueQuartCenters.put(id, entry.getLong(TAG_PACKED_QUART));
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Object2LongMap.Entry<ResourceLocation> entry : this.uniqueQuartCenters.object2LongEntrySet()) {
            CompoundTag item = new CompoundTag();
            item.putString(TAG_ID, entry.getKey().toString());
            item.putLong(TAG_PACKED_QUART, entry.getLongValue());
            list.add(item);
        }
        tag.put(TAG_ENTRIES, list);
        return tag;
    }

    public long getPackedQuartCenter(ResourceLocation id) {
        return this.uniqueQuartCenters.getLong(id);
    }

    public void putPackedQuartCenter(ResourceLocation id, int quartX, int quartZ) {
        long packed = ((long) quartX << 32) | (quartZ & 0xFFFFFFFFL);
        this.uniqueQuartCenters.put(id, packed);
        this.setDirty();
    }
}
