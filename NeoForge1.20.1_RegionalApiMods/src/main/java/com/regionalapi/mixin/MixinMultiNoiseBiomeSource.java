package com.regionalapi.mixin;

import com.regionalapi.core.region.BiomeMaskEvaluationHelper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Injects into {@link MultiNoiseBiomeSource#getNoiseBiome(int, int, int, Climate.Sampler)} at {@code HEAD}
 * to perform 2D-to-3D horizontal column reuse from {@link com.regionalapi.core.cache.ThreadLocalRegionCache}
 * and lazy {@link Climate.Sampler} bypass before vanilla invokes {@code sampler.sample(qx, qy, qz)}.
 */
@Mixin(MultiNoiseBiomeSource.class)
public abstract class MixinMultiNoiseBiomeSource {

    @Inject(
            method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void regionalapi$onGetNoiseBiomeHead(
            int qx,
            int qy,
            int qz,
            Climate.Sampler sampler,
            CallbackInfoReturnable<Holder<Biome>> cir
    ) {
        Holder<Biome> override = BiomeMaskEvaluationHelper.evaluateBiomeOverride(qx, qy, qz, sampler);
        if (override != null) {
            cir.setReturnValue(override);
        }
    }
}
