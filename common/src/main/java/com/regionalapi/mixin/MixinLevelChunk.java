package com.regionalapi.mixin;

import com.regionalapi.core.cache.IRegionChunkData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Propagates populated {@link IRegionChunkData} from {@link ProtoChunk} to {@link LevelChunk}.
 */
@Mixin(LevelChunk.class)
public abstract class MixinLevelChunk {

    @Inject(
            method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V",
            at = @At("RETURN"),
            require = 0
    )
    private void regional$copyFromProtoChunk(
            ServerLevel serverLevel,
            ProtoChunk protoChunk,
            LevelChunk.PostLoadProcessor postLoadProcessor,
            CallbackInfo ci
    ) {
        if (protoChunk instanceof IRegionChunkData source && source.regional$isInitialized()) {
            if ((Object) this instanceof IRegionChunkData target) {
                if (source.regional$isHomogeneous()) {
                    target.regional$setHomogeneous(source.regional$getSingleRegionId());
                } else {
                    target.regional$setBorder(source.regional$getBorderQuartGrid());
                }
            }
        }
    }
}
