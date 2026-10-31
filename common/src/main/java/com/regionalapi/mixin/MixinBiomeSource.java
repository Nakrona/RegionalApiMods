package com.regionalapi.mixin;

import com.regionalapi.core.region.BiomeMaskEvaluationHelper;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Fallback mixin on the base {@link BiomeSource} class so custom non-MultiNoise {@link BiomeSource}
 * implementations also benefit from RegionalAPI's 2D-to-3D column reuse and lazy sampler bypass.
 */
@Mixin(BiomeSource.class)
public abstract class MixinBiomeSource implements BiomeResolver {

    /**
     * Exposes a bridge helper for custom BiomeSource subclasses that delegate through {@link BiomeResolver}.
     */
    protected Holder<Biome> regionalapi$resolveCustomBiomeSourceOverride(int qx, int qy, int qz, Climate.Sampler sampler) {
        if ((Object) this instanceof MultiNoiseBiomeSource) {
            return null;
        }
        return BiomeMaskEvaluationHelper.evaluateBiomeOverride(qx, qy, qz, sampler);
    }
}
