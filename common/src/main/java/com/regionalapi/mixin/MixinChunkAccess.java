package com.regionalapi.mixin;

import com.regionalapi.core.cache.IRegionChunkData;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin injecting the Zero-GC {@link IRegionChunkData} Duck Interface into {@link ChunkAccess}.
 * Enforces strict single-region per chunk homogeneity (1 chunk = max 1 region).
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccess implements IRegionChunkData {

    @Unique
    private boolean regional$initialized = false;

    @Unique
    private long regional$populatedEpoch = 0L;

    @Unique
    private boolean regional$homogeneous = true;

    @Unique
    private short regional$singleRegionId = RegionRegistry.FALLBACK_WILDS_ID;

    @Unique
    private short[] regional$borderQuartGrid = null;

    @Override
    public boolean regional$isInitialized() {
        return this.regional$initialized
                && RegionRegistry.getInstance().isFrozen()
                && this.regional$populatedEpoch == ThreadLocalRegionCache.getCurrentEpoch();
    }

    @Override
    public boolean regional$isHomogeneous() {
        return true;
    }

    @Override
    public short regional$getSingleRegionId() {
        return this.regional$singleRegionId;
    }

    @Override
    public short[] regional$getBorderQuartGrid() {
        return this.regional$borderQuartGrid;
    }

    @Override
    public short regional$getRegionAtQuart(int qx, int qz) {
        return this.regional$singleRegionId;
    }

    @Override
    public void regional$setHomogeneous(short regionId) {
        boolean ready = RegionRegistry.getInstance().isFrozen() && ThreadLocalRegionCache.getActiveWorldSeed() != 0L;
        this.regional$initialized = ready;
        this.regional$populatedEpoch = ready ? ThreadLocalRegionCache.getCurrentEpoch() : 0L;
        this.regional$homogeneous = true;
        this.regional$singleRegionId = regionId;
        this.regional$borderQuartGrid = null;
    }

    @Override
    public void regional$setBorder(short[] quartGrid16) {
        short uniformId = (quartGrid16 != null && quartGrid16.length > 0)
                ? quartGrid16[0]
                : RegionRegistry.FALLBACK_WILDS_ID;
        regional$setHomogeneous(uniformId);
    }
}
