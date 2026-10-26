package com.regionalapi.core.region;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Optional;

/**
 * Defines vertical-aware 3D biome override rules for a region without requiring
 * world or block lookups (100% thread-safe and deadlock-free).
 * <p>
 * Supports two vertical discrimination modes:
 * <ul>
 *     <li>{@link VerticalMode#STATIC_Y_THRESHOLD}: Pure integer comparison on {@code quartY},
 *     allowing immediate lazy bypass of {@link Climate.Sampler} density evaluation.</li>
 *     <li>{@link VerticalMode#TERRAIN_FOLLOWING_DEPTH}: Evaluates the {@code depth} density parameter
 *     from {@link Climate.Sampler} ({@code depth > 0} indicates underground/cave interior even inside tall mountains).</li>
 * </ul>
 */
public final class BiomeMaskRule {

    public static final Codec<BiomeMaskRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            VerticalMode.CODEC.fieldOf("vertical_mode").forGetter(BiomeMaskRule::verticalMode),
            Codec.INT.optionalFieldOf("surface_quart_y_threshold", 15).forGetter(BiomeMaskRule::surfaceQuartYThreshold),
            ResourceLocation.CODEC.optionalFieldOf("surface_biome").forGetter(BiomeMaskRule::surfaceBiomeId),
            ResourceLocation.CODEC.optionalFieldOf("cave_biome").forGetter(BiomeMaskRule::caveBiomeId)
    ).apply(instance, BiomeMaskRule::new));

    private final VerticalMode verticalMode;
    private final int surfaceQuartYThreshold;
    private final Optional<ResourceLocation> surfaceBiomeId;
    private final Optional<ResourceLocation> caveBiomeId;

    // Direct Holder<Biome> references frozen during ServerAboutToStartEvent
    private volatile Holder<Biome> resolvedSurfaceBiome;
    private volatile Holder<Biome> resolvedCaveBiome;

    public BiomeMaskRule(
            VerticalMode verticalMode,
            int surfaceQuartYThreshold,
            Optional<ResourceLocation> surfaceBiomeId,
            Optional<ResourceLocation> caveBiomeId
    ) {
        this.verticalMode = verticalMode;
        this.surfaceQuartYThreshold = surfaceQuartYThreshold;
        this.surfaceBiomeId = surfaceBiomeId;
        this.caveBiomeId = caveBiomeId;
    }

    public VerticalMode verticalMode() {
        return this.verticalMode;
    }

    public int surfaceQuartYThreshold() {
        return this.surfaceQuartYThreshold;
    }

    public Optional<ResourceLocation> surfaceBiomeId() {
        return this.surfaceBiomeId;
    }

    public Optional<ResourceLocation> caveBiomeId() {
        return this.caveBiomeId;
    }

    /**
     * Resolves and caches direct {@link Holder} references from the frozen server biome registry.
     * Invoked once during {@code ServerAboutToStartEvent}; never invoked during worldgen.
     *
     * @param biomeRegistry The frozen server-side biome registry.
     */
    public void freezeHolders(Registry<Biome> biomeRegistry) {
        this.resolvedSurfaceBiome = this.surfaceBiomeId
                .flatMap(id -> biomeRegistry.getHolder(ResourceKey.create(biomeRegistry.key(), id)))
                .orElse(null);
        this.resolvedCaveBiome = this.caveBiomeId
                .flatMap(id -> biomeRegistry.getHolder(ResourceKey.create(biomeRegistry.key(), id)))
                .orElse(null);
    }

    /**
     * Binds direct {@link Holder} instances programmatically (for runtime or test registration).
     *
     * @param surfaceHolder Direct holder for surface biome (nullable).
     * @param caveHolder    Direct holder for cave biome (nullable).
     */
    public void bindHoldersDirectly(Holder<Biome> surfaceHolder, Holder<Biome> caveHolder) {
        this.resolvedSurfaceBiome = surfaceHolder;
        this.resolvedCaveBiome = caveHolder;
    }

    /**
     * Evaluates an unconditional biome override that does NOT require sampling {@link Climate.Sampler}.
     * <p>
     * If this returns a non-null {@link Holder}, the caller in {@code BiomeSource.getNoiseBiome}
     * can return immediately BEFORE vanilla evaluates any 3D density functions.
     *
     * @param quartY The vertical QuartPos Y coordinate ({@code blockY >> 2}).
     * @return The cached {@link Holder} if an unconditional rule matches, or {@code null} if sampling is required or no override applies.
     */
    public Holder<Biome> evaluateUnconditional(int quartY) {
        if (this.verticalMode == VerticalMode.STATIC_Y_THRESHOLD) {
            if (quartY > this.surfaceQuartYThreshold) {
                return this.resolvedSurfaceBiome;
            } else {
                return this.resolvedCaveBiome;
            }
        }
        // If both surface and cave resolve to the exact same non-null biome holder, it is also unconditional
        if (this.resolvedSurfaceBiome != null && this.resolvedSurfaceBiome == this.resolvedCaveBiome) {
            return this.resolvedSurfaceBiome;
        }
        return null;
    }

    /**
     * Evaluates a whole-section unconditional biome override covering the 4-quart vertical span
     * {@code [baseQuartY, baseQuartY + 3]} of a {@code LevelChunkSection}.
     *
     * @param baseQuartY The minimum QuartPos Y coordinate of the 16x16x16 section.
     * @return A single uniform {@link Holder} if all 4 vertical quarts unconditionally resolve to the same biome, else {@code null}.
     */
    public Holder<Biome> evaluateSectionUnconditional(int baseQuartY) {
        if (this.verticalMode == VerticalMode.STATIC_Y_THRESHOLD) {
            // Entire section [baseQuartY, baseQuartY + 3] is strictly above threshold
            if (baseQuartY > this.surfaceQuartYThreshold) {
                return this.resolvedSurfaceBiome;
            }
            // Entire section [baseQuartY, baseQuartY + 3] is at or below threshold
            if (baseQuartY + 3 <= this.surfaceQuartYThreshold) {
                return this.resolvedCaveBiome;
            }
            // Section straddles threshold, still uniform if both holders are identical
            if (this.resolvedSurfaceBiome != null && this.resolvedSurfaceBiome == this.resolvedCaveBiome) {
                return this.resolvedSurfaceBiome;
            }
            return null;
        }
        if (this.resolvedSurfaceBiome != null && this.resolvedSurfaceBiome == this.resolvedCaveBiome) {
            return this.resolvedSurfaceBiome;
        }
        return null;
    }

    /**
     * Evaluates a terrain-following depth biome override using only the {@code depth} density function
     * from {@link Climate.Sampler}, avoiding evaluating the other 5 climate density channels when possible.
     *
     * @param quartX  Horizontal QuartPos X.
     * @param quartY  Vertical QuartPos Y.
     * @param quartZ  Horizontal QuartPos Z.
     * @param sampler The active {@link Climate.Sampler}.
     * @return The resolved {@link Holder}, or {@code null} if vanilla fallback should be used.
     */
    public Holder<Biome> evaluateDepthAware(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        if (this.verticalMode == VerticalMode.STATIC_Y_THRESHOLD) {
            return evaluateUnconditional(quartY);
        }

        if (this.resolvedSurfaceBiome == null && this.resolvedCaveBiome == null) {
            return null;
        }

        int blockX = quartX << 2;
        int blockY = quartY << 2;
        int blockZ = quartZ << 2;

        // Evaluate only the depth density function: depth > 0.0D mathematically denotes underground/cave
        double depthValue = sampler.depth().compute(new DensityFunction.SinglePointContext(blockX, blockY, blockZ));
        if (depthValue > 0.0D) {
            return this.resolvedCaveBiome;
        } else {
            return this.resolvedSurfaceBiome;
        }
    }

    public Holder<Biome> getResolvedSurfaceBiome() {
        return this.resolvedSurfaceBiome;
    }

    public Holder<Biome> getResolvedCaveBiome() {
        return this.resolvedCaveBiome;
    }

    /**
     * Vertical discrimination mode for 3D Biome Masking.
     */
    public enum VerticalMode implements StringRepresentable {
        STATIC_Y_THRESHOLD("static_y_threshold"),
        TERRAIN_FOLLOWING_DEPTH("terrain_following_depth");

        public static final Codec<VerticalMode> CODEC = StringRepresentable.fromEnum(VerticalMode::values);

        private final String serializedName;

        VerticalMode(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return this.serializedName;
        }
    }
}
