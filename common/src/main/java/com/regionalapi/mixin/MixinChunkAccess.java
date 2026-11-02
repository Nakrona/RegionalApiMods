package com.regionalapi.mixin;

import com.regionalapi.core.cache.IRegionChunkData;
import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Arrays;

/**
 * Mixin injecting the Zero-GC {@link IRegionChunkData} Duck Interface into {@link ChunkAccess}.
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
        return this.regional$homogeneous;
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
        if (this.regional$homogeneous || this.regional$borderQuartGrid == null) {
            return this.regional$singleRegionId;
        }
        int columnIndex = ((qz & 3) << 2) | (qx & 3);
        return this.regional$borderQuartGrid[columnIndex];
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
        boolean ready = RegionRegistry.getInstance().isFrozen() && ThreadLocalRegionCache.getActiveWorldSeed() != 0L;
        this.regional$initialized = ready;
        this.regional$populatedEpoch = ready ? ThreadLocalRegionCache.getCurrentEpoch() : 0L;
        this.regional$homogeneous = false;
        if (this.regional$borderQuartGrid == null || this.regional$borderQuartGrid.length != 16) {
            this.regional$borderQuartGrid = Arrays.copyOf(quartGrid16, 16);
        } else {
            System.arraycopy(quartGrid16, 0, this.regional$borderQuartGrid, 0, 16);
        }
        this.regional$singleRegionId = this.regional$borderQuartGrid[0];
    }
}
