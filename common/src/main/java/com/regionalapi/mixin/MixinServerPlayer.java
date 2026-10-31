package com.regionalapi.mixin;

import com.regionalapi.core.cache.IPlayerRegionTracker;
import com.regionalapi.platform.CommonRegionMovementTracker;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin injecting {@link IPlayerRegionTracker} into {@link ServerPlayer} and evaluating
 * zero-overhead chunk crossing and conditional border-chunk QuartPos polling.
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer implements IPlayerRegionTracker {

    @Unique
    private short regional$lastRegionId = -1;

    @Unique
    private boolean regional$inBorderChunk = false;

    @Unique
    private int regional$lastQuartX = Integer.MIN_VALUE;

    @Unique
    private int regional$lastQuartZ = Integer.MIN_VALUE;

    @Unique
    private int regional$lastChunkX = Integer.MIN_VALUE;

    @Unique
    private int regional$lastChunkZ = Integer.MIN_VALUE;

    @Override
    public short regional$getLastRegionId() {
        return this.regional$lastRegionId;
    }

    @Override
    public void regional$setLastRegionId(short regionId) {
        this.regional$lastRegionId = regionId;
    }

    @Override
    public boolean regional$isInBorderChunk() {
        return this.regional$inBorderChunk;
    }

    @Override
    public void regional$setInBorderChunk(boolean inBorderChunk) {
        this.regional$inBorderChunk = inBorderChunk;
    }

    @Override
    public int regional$getLastQuartX() {
        return this.regional$lastQuartX;
    }

    @Override
    public int regional$getLastQuartZ() {
        return this.regional$lastQuartZ;
    }

    @Override
    public void regional$updateLastQuartPos(int qx, int qz) {
        this.regional$lastQuartX = qx;
        this.regional$lastQuartZ = qz;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void regional$onTick(CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        int chunkX = self.getBlockX() >> 4;
        int chunkZ = self.getBlockZ() >> 4;

        if (chunkX != this.regional$lastChunkX || chunkZ != this.regional$lastChunkZ) {
            this.regional$lastChunkX = chunkX;
            this.regional$lastChunkZ = chunkZ;
            CommonRegionMovementTracker.onPlayerEnterChunk(self, chunkX, chunkZ);
        } else if (this.regional$inBorderChunk) {
            CommonRegionMovementTracker.onPlayerTick(self);
        }
    }
}
