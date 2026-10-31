package com.regionalapi.mixin;

import com.regionalapi.core.cache.ThreadLocalRegionCache;
import com.regionalapi.core.region.BiomeMaskRule;
import com.regionalapi.core.region.RegionDefinition;
import com.regionalapi.core.region.RegionRegistry;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Whole-Section Palette Short-Circuit optimization for {@link LevelChunkSection#fillBiomesFromNoise}.
 * <p>
 * If the current chunk is marked {@code isHomogeneous == true} in {@link ThreadLocalRegionCache}
 * AND the region's {@link BiomeMaskRule} covers the entire vertical {@code 4x4x4} QuartPos span
 * {@code [qy, qy + 3]} of this section with a single unconditional {@code Holder<Biome>},
 * bypasses the 64-iteration 3D noise loop completely and assigns a single-value
 * {@link PalettedContainer} initialized with that {@code Holder<Biome>}.
 */
@Mixin(LevelChunkSection.class)
public abstract class MixinLevelChunkSection {

    @Shadow
    private PalettedContainerRO<Holder<Biome>> biomes;

    @Inject(
            method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;III)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void regionalapi$shortCircuitHomogeneousSectionPalette(
            BiomeResolver resolver,
            Climate.Sampler sampler,
            int qx,
            int qy,
            int qz,
            CallbackInfo ci
    ) {
        RegionRegistry registry = RegionRegistry.getInstance();
        if (!registry.isFrozen()) {
            return;
        }

        int chunkX = qx >> 2;
        int chunkZ = qz >> 2;

        ThreadLocalRegionCache cache = ThreadLocalRegionCache.get();
        int slot = cache.ensureChunkSlot(chunkX, chunkZ);

        if (!cache.isSlotHomogeneous(slot)) {
            return;
        }

        short regionId = cache.getSlotFirstRegionId(slot);
        RegionDefinition definition = registry.getByRawId(regionId);
        if (definition == null) {
            return;
        }

        BiomeMaskRule maskRule = definition.biomeMaskRule();
        if (maskRule == null) {
            return;
        }

        Holder<Biome> uniformSectionBiome = maskRule.evaluateSectionUnconditional(qy);
        if (uniformSectionBiome != null) {
            PalettedContainer<Holder<Biome>> currentContainer = this.biomes.recreate();
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        currentContainer.getAndSetUnchecked(x, y, z, uniformSectionBiome);
                    }
                }
            }
            this.biomes = currentContainer;
            ci.cancel();
        }
    }
}
