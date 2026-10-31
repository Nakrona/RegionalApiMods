package com.regionalapi.core.region;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Immutable definition of a procedural geopolitical/ecological region in RegionalAPI.
 * <p>
 * Holds Laguerre-Voronoi power diagram weighting ({@code weight}), uniqueness constraints
 * ({@code isUnique}), selection weight for infinite tessellation, and optional 3D
 * {@link BiomeMaskRule} overrides.
 */
public final class RegionDefinition {

    public static final Codec<RegionDefinition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(RegionDefinition::id),
            Codec.INT.optionalFieldOf("weight", 32).forGetter(RegionDefinition::weight),
            Codec.INT.optionalFieldOf("selection_ticket_weight", 10).forGetter(RegionDefinition::selectionTicketWeight),
            Codec.BOOL.optionalFieldOf("is_unique", false).forGetter(RegionDefinition::isUnique),
            Codec.INT.optionalFieldOf("unique_search_min_radius_blocks", 512).forGetter(RegionDefinition::uniqueSearchMinRadiusBlocks),
            Codec.INT.optionalFieldOf("unique_search_max_radius_blocks", 4096).forGetter(RegionDefinition::uniqueSearchMaxRadiusBlocks),
            BiomeMaskRule.CODEC.optionalFieldOf("biome_mask_rule").forGetter(RegionDefinition::biomeMaskRuleOptional)
    ).apply(instance, RegionDefinition::new));

    private final ResourceLocation id;
    private final int weight;
    private final long weightSquaredQuart;
    private final int selectionTicketWeight;
    private final boolean isUnique;
    private final int uniqueSearchMinRadiusBlocks;
    private final int uniqueSearchMaxRadiusBlocks;
    private final BiomeMaskRule biomeMaskRule;

    // Assigned when RegionRegistry freezes on ServerAboutToStartEvent
    private short rawId = -1;

    public RegionDefinition(
            ResourceLocation id,
            int weight,
            int selectionTicketWeight,
            boolean isUnique,
            int uniqueSearchMinRadiusBlocks,
            int uniqueSearchMaxRadiusBlocks,
            Optional<BiomeMaskRule> biomeMaskRule
    ) {
        this.id = id;
        this.weight = Math.max(0, weight);
        // Convert block weight to QuartPos weight (weight >> 2) and pre-square for zero-mul hot paths
        long quartWeight = this.weight >> 2;
        this.weightSquaredQuart = quartWeight * quartWeight;
        this.selectionTicketWeight = Math.max(1, selectionTicketWeight);
        this.isUnique = isUnique;
        this.uniqueSearchMinRadiusBlocks = Math.max(0, uniqueSearchMinRadiusBlocks);
        this.uniqueSearchMaxRadiusBlocks = Math.max(this.uniqueSearchMinRadiusBlocks + 64, uniqueSearchMaxRadiusBlocks);
        this.biomeMaskRule = biomeMaskRule.orElse(null);
    }

    public ResourceLocation id() {
        return this.id;
    }

    /**
     * Laguerre-Voronoi radius weight {@code wi} in block units.
     */
    public int weight() {
        return this.weight;
    }

    /**
     * Pre-squared Laguerre-Voronoi weight {@code wi^2} in {@code QuartPos} units.
     * Used directly in {@code (x - xi)^2 + (z - zi)^2 - wi^2}.
     */
    public long weightSquaredQuart() {
        return this.weightSquaredQuart;
    }

    /**
     * Relative ticket frequency when choosing regions for Voronoi cells.
     */
    public int selectionTicketWeight() {
        return this.selectionTicketWeight;
    }

    /**
     * Whether this region spawns exactly once in the world and is indexed in the spatial QuadTree.
     */
    public boolean isUnique() {
        return this.isUnique;
    }

    public int uniqueSearchMinRadiusBlocks() {
        return this.uniqueSearchMinRadiusBlocks;
    }

    public int uniqueSearchMaxRadiusBlocks() {
        return this.uniqueSearchMaxRadiusBlocks;
    }

    /**
     * Nullable direct reference for zero-allocation hot-path checks.
     */
    public BiomeMaskRule biomeMaskRule() {
        return this.biomeMaskRule;
    }

    public Optional<BiomeMaskRule> biomeMaskRuleOptional() {
        return Optional.ofNullable(this.biomeMaskRule);
    }

    public short rawId() {
        return this.rawId;
    }

    public void assignRawId(short rawId) {
        this.rawId = rawId;
    }
}
