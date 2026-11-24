package com.regionalapi.core.region;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional, declarative metadata registry associating biomes, structures, and tags with
 * {@link RegionDefinition} identifiers.
 * <p>
 * Pure core introspection layer designed for One-Way Addon interoperability and region-scoped
 * {@code /locate region <region> [biome|structure]} tab-completion:
 * <ul>
 *     <li><b>100% Optional</b>: Regions without explicit registrations automatically fall back to
 *     global Minecraft registries during tab-completion (unless constrained by a dual {@link BiomeMaskRule}).</li>
 *     <li><b>Inter-Addon Discovery</b>: Downstream addons can declare their region-bound biomes and
 *     structures here so third-party mods (maps, quests, compasses, bridges) can introspect them
 *     solely through {@link com.regionalapi.api.RegionalApi} without hard cross-addon dependencies.</li>
 *     <li><b>Zero Worldgen Overhead</b>: Never queried on chunk/worldgen hot paths.</li>
 * </ul>
 */
public final class RegionContentRegistry {

    private static final RegionContentRegistry INSTANCE = new RegionContentRegistry();

    private final Map<ResourceLocation, Set<ResourceLocation>> explicitBiomes = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, Set<TagKey<Biome>>> explicitBiomeTags = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, Set<ResourceLocation>> explicitStructures = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, Set<TagKey<Structure>>> explicitStructureTags = new ConcurrentHashMap<>();

    private RegionContentRegistry() {
    }

    public static RegionContentRegistry getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // Biome Registration
    // -------------------------------------------------------------------------

    public void registerBiome(ResourceLocation regionId, ResourceLocation biomeId) {
        validateNonNull(regionId, "regionId");
        validateNonNull(biomeId, "biomeId");
        this.explicitBiomes.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet()).add(biomeId);
    }

    public void registerBiomes(ResourceLocation regionId, Collection<ResourceLocation> biomeIds) {
        validateNonNull(regionId, "regionId");
        validateNonNull(biomeIds, "biomeIds");
        if (biomeIds.isEmpty()) {
            return;
        }
        Set<ResourceLocation> target = this.explicitBiomes.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet());
        for (ResourceLocation biomeId : biomeIds) {
            if (biomeId != null) {
                target.add(biomeId);
            }
        }
    }

    public void registerBiomeTag(ResourceLocation regionId, TagKey<Biome> biomeTag) {
        validateNonNull(regionId, "regionId");
        validateNonNull(biomeTag, "biomeTag");
        this.explicitBiomeTags.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet()).add(biomeTag);
    }

    public void registerBiomeTags(ResourceLocation regionId, Collection<TagKey<Biome>> biomeTags) {
        validateNonNull(regionId, "regionId");
        validateNonNull(biomeTags, "biomeTags");
        if (biomeTags.isEmpty()) {
            return;
        }
        Set<TagKey<Biome>> target = this.explicitBiomeTags.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet());
        for (TagKey<Biome> tag : biomeTags) {
            if (tag != null) {
                target.add(tag);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Structure Registration
    // -------------------------------------------------------------------------

    public void registerStructure(ResourceLocation regionId, ResourceLocation structureId) {
        validateNonNull(regionId, "regionId");
        validateNonNull(structureId, "structureId");
        this.explicitStructures.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet()).add(structureId);
    }

    public void registerStructures(ResourceLocation regionId, Collection<ResourceLocation> structureIds) {
        validateNonNull(regionId, "regionId");
        validateNonNull(structureIds, "structureIds");
        if (structureIds.isEmpty()) {
            return;
        }
        Set<ResourceLocation> target = this.explicitStructures.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet());
        for (ResourceLocation structureId : structureIds) {
            if (structureId != null) {
                target.add(structureId);
            }
        }
    }

    public void registerStructureTag(ResourceLocation regionId, TagKey<Structure> structureTag) {
        validateNonNull(regionId, "regionId");
        validateNonNull(structureTag, "structureTag");
        this.explicitStructureTags.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet()).add(structureTag);
    }

    public void registerStructureTags(ResourceLocation regionId, Collection<TagKey<Structure>> structureTags) {
        validateNonNull(regionId, "regionId");
        validateNonNull(structureTags, "structureTags");
        if (structureTags.isEmpty()) {
            return;
        }
        Set<TagKey<Structure>> target = this.explicitStructureTags.computeIfAbsent(regionId, key -> ConcurrentHashMap.newKeySet());
        for (TagKey<Structure> tag : structureTags) {
            if (tag != null) {
                target.add(tag);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Introspection & Queries
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if an addon explicitly registered at least one biome ID or biome tag for {@code regionId}.
     */
    public boolean hasExplicitBiomes(ResourceLocation regionId) {
        if (regionId == null) {
            return false;
        }
        Set<ResourceLocation> ids = this.explicitBiomes.get(regionId);
        if (ids != null && !ids.isEmpty()) {
            return true;
        }
        Set<TagKey<Biome>> tags = this.explicitBiomeTags.get(regionId);
        return tags != null && !tags.isEmpty();
    }

    /**
     * Returns {@code true} if {@code regionId} has any registered biomes/tags OR a {@link BiomeMaskRule}
     * declaring a surface or cave biome override.
     */
    public boolean hasRegisteredBiomes(ResourceLocation regionId) {
        if (hasExplicitBiomes(regionId)) {
            return true;
        }
        BiomeMaskRule maskRule = getBiomeMaskRule(regionId);
        if (maskRule == null) {
            return false;
        }
        return maskRule.surfaceBiomeId().isPresent() || maskRule.caveBiomeId().isPresent();
    }

    /**
     * Returns {@code true} if {@code regionId} has a dual {@link BiomeMaskRule} (both surface and cave biomes set),
     * meaning its 3D volume is mathematically restricted to those masked biomes.
     */
    public boolean hasDualBiomeMask(ResourceLocation regionId) {
        BiomeMaskRule maskRule = getBiomeMaskRule(regionId);
        return maskRule != null
                && maskRule.surfaceBiomeId().isPresent()
                && maskRule.caveBiomeId().isPresent();
    }

    /**
     * Returns an unmodifiable snapshot of all biome IDs associated with {@code regionId},
     * combining explicitly registered biome IDs and any biome IDs declared in the region's {@link BiomeMaskRule}.
     */
    public Set<ResourceLocation> getRegisteredBiomes(ResourceLocation regionId) {
        if (regionId == null) {
            return Collections.emptySet();
        }
        Set<ResourceLocation> combined = new LinkedHashSet<>();

        BiomeMaskRule maskRule = getBiomeMaskRule(regionId);
        if (maskRule != null) {
            maskRule.surfaceBiomeId().ifPresent(combined::add);
            maskRule.caveBiomeId().ifPresent(combined::add);
        }

        Set<ResourceLocation> explicit = this.explicitBiomes.get(regionId);
        if (explicit != null) {
            combined.addAll(explicit);
        }

        return combined.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(combined);
    }

    /**
     * Returns an unmodifiable set of biome {@link TagKey} entries explicitly registered for {@code regionId}.
     */
    public Set<TagKey<Biome>> getRegisteredBiomeTags(ResourceLocation regionId) {
        if (regionId == null) {
            return Collections.emptySet();
        }
        Set<TagKey<Biome>> tags = this.explicitBiomeTags.get(regionId);
        return (tags == null || tags.isEmpty()) ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(tags));
    }

    /**
     * Returns {@code true} if an addon explicitly registered at least one structure ID or structure tag for {@code regionId}.
     */
    public boolean hasRegisteredStructures(ResourceLocation regionId) {
        if (regionId == null) {
            return false;
        }
        Set<ResourceLocation> ids = this.explicitStructures.get(regionId);
        if (ids != null && !ids.isEmpty()) {
            return true;
        }
        Set<TagKey<Structure>> tags = this.explicitStructureTags.get(regionId);
        return tags != null && !tags.isEmpty();
    }

    /**
     * Returns an unmodifiable set of structure IDs explicitly registered for {@code regionId}.
     */
    public Set<ResourceLocation> getRegisteredStructures(ResourceLocation regionId) {
        if (regionId == null) {
            return Collections.emptySet();
        }
        Set<ResourceLocation> ids = this.explicitStructures.get(regionId);
        return (ids == null || ids.isEmpty()) ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    /**
     * Returns an unmodifiable set of structure {@link TagKey} entries explicitly registered for {@code regionId}.
     */
    public Set<TagKey<Structure>> getRegisteredStructureTags(ResourceLocation regionId) {
        if (regionId == null) {
            return Collections.emptySet();
        }
        Set<TagKey<Structure>> tags = this.explicitStructureTags.get(regionId);
        return (tags == null || tags.isEmpty()) ? Collections.emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(tags));
    }

    /**
     * Checks whether {@code biomeId} is directly registered for {@code regionId} or declared in its {@link BiomeMaskRule}.
     */
    public boolean isBiomeRegistered(ResourceLocation regionId, ResourceLocation biomeId) {
        if (regionId == null || biomeId == null) {
            return false;
        }
        Set<ResourceLocation> explicit = this.explicitBiomes.get(regionId);
        if (explicit != null && explicit.contains(biomeId)) {
            return true;
        }
        BiomeMaskRule maskRule = getBiomeMaskRule(regionId);
        if (maskRule != null) {
            if (maskRule.surfaceBiomeId().isPresent() && biomeId.equals(maskRule.surfaceBiomeId().get())) {
                return true;
            }
            if (maskRule.caveBiomeId().isPresent() && biomeId.equals(maskRule.caveBiomeId().get())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tag-aware check of whether {@code biomeHolder} matches any direct biome ID, biome tag,
     * or {@link BiomeMaskRule} declared for {@code regionId}.
     */
    public boolean isBiomeRegistered(ResourceLocation regionId, Holder<Biome> biomeHolder) {
        if (regionId == null || biomeHolder == null) {
            return false;
        }
        Optional<ResourceKey<Biome>> keyOpt = biomeHolder.unwrapKey();
        if (keyOpt.isPresent() && isBiomeRegistered(regionId, keyOpt.get().location())) {
            return true;
        }
        Set<TagKey<Biome>> tags = this.explicitBiomeTags.get(regionId);
        if (tags != null) {
            for (TagKey<Biome> tag : tags) {
                if (biomeHolder.is(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether {@code structureId} is directly registered for {@code regionId}.
     */
    public boolean isStructureRegistered(ResourceLocation regionId, ResourceLocation structureId) {
        if (regionId == null || structureId == null) {
            return false;
        }
        Set<ResourceLocation> explicit = this.explicitStructures.get(regionId);
        return explicit != null && explicit.contains(structureId);
    }

    /**
     * Tag-aware check of whether {@code structureHolder} matches any direct structure ID
     * or structure tag declared for {@code regionId}.
     */
    public boolean isStructureRegistered(ResourceLocation regionId, Holder<Structure> structureHolder) {
        if (regionId == null || structureHolder == null) {
            return false;
        }
        Optional<ResourceKey<Structure>> keyOpt = structureHolder.unwrapKey();
        if (keyOpt.isPresent() && isStructureRegistered(regionId, keyOpt.get().location())) {
            return true;
        }
        Set<TagKey<Structure>> tags = this.explicitStructureTags.get(regionId);
        if (tags != null) {
            for (TagKey<Structure> tag : tags) {
                if (structureHolder.is(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BiomeMaskRule getBiomeMaskRule(ResourceLocation regionId) {
        RegionRegistry registry = RegionRegistry.getInstance();
        short rawId = registry.getRawId(regionId);
        if (rawId >= 0) {
            RegionDefinition def = registry.getByRawId(rawId);
            if (def != null) {
                return def.biomeMaskRule();
            }
        }
        // Pre-freeze lookup fallback during mod initialization
        for (RegionDefinition def : registry.getAllDefinitions()) {
            if (regionId.equals(def.id())) {
                return def.biomeMaskRule();
            }
        }
        return null;
    }

    private static void validateNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null.");
        }
    }
}
