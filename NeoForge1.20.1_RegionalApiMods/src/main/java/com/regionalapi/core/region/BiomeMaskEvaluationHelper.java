package com.regionalapi.core.region;

import com.regionalapi.core.cache.ThreadLocalRegionCache;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Shared Zero-GC static helper invoked by {@code MixinMultiNoiseBiomeSource} and {@code MixinBiomeSource}
 * to resolve 2D-to-3D column reuse and lazy {@link Climate.Sampler} bypass.
 */
public final class BiomeMaskEvaluationHelper {

    private BiomeMaskEvaluationHelper() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * Evaluates the RegionalAPI 3D biome override at {@code (qx, qy, qz)} using the current thread's
     * {@link ThreadLocalRegionCache}.
     * <p>
     * Performs lazy {@link Climate.Sampler} bypass: if the region defines an unconditional override
     * for {@code qy} (such as {@code STATIC_Y_THRESHOLD}), returns the cached {@code Holder<Biome>}
     * immediately without touching {@code sampler}.
     *
     * @param qx      Horizontal QuartPos X.
     * @param qy      Vertical QuartPos Y.
     * @param qz      Horizontal QuartPos Z.
     * @param sampler The active {@link Climate.Sampler}.
     * @return The overriding {@link Holder}, or {@code null} if vanilla biome resolution should proceed.
     */
    public static Holder<Biome> evaluateBiomeOverride(int qx, int qy, int qz, Climate.Sampler sampler) {
        RegionRegistry registry = RegionRegistry.getInstance();
        if (!registry.isFrozen()) {
            return null;
        }

        short regionId = ThreadLocalRegionCache.get().getRegionAtQuart(qx, qz);
        RegionDefinition definition = registry.getByRawId(regionId);
        if (definition == null) {
            return null;
        }

        BiomeMaskRule maskRule = definition.biomeMaskRule();
        if (maskRule == null) {
            return null;
        }

        // Fast-path 1: Unconditional evaluation (bypasses all 6 Climate.Sampler 3D density functions)
        Holder<Biome> unconditional = maskRule.evaluateUnconditional(qy);
        if (unconditional != null) {
            return unconditional;
        }

        // Fast-path 2: Terrain-following depth evaluation (evaluates only sampler.depth(), skipping 5 other channels)
        if (maskRule.verticalMode() == BiomeMaskRule.VerticalMode.TERRAIN_FOLLOWING_DEPTH) {
            return maskRule.evaluateDepthAware(qx, qy, qz, sampler);
        }

        return null;
    }
}
