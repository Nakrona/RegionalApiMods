package com.regionalapi.core.region;

import com.regionalapi.RegionalModConstants;
import com.regionalapi.core.math.DeterministicHash;
import com.regionalapi.core.math.VoronoiEngine2D;
import it.unimi.dsi.fastutil.objects.Object2ShortOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central deterministic registry mapping {@link ResourceLocation} region identifiers to compact
 * primitive {@code short} IDs ({@code 0, 1, 2...}) and flat lookup arrays.
 * <p>
 * During {@code ServerAboutToStartEvent}, all registered {@link RegionDefinition} instances are
 * sorted deterministically, assigned sequential primitive {@code short} IDs, bound to direct
 * {@code Holder<Biome>} references, and indexed into a flat ticket table and a 2D QuadTree for
 * {@code isUnique == true} regions.
 */
public final class RegionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegionRegistry.class);

    /**
     * Reserved primitive ID {@code 0} representing the fallback Wilds / unmapped exterior region.
     */
    public static final short FALLBACK_WILDS_ID = 0;

    public static final ResourceLocation FALLBACK_WILDS_LOCATION =
            ResourceLocation.fromNamespaceAndPath(RegionalModConstants.MOD_ID, "wilds");

    private static final RegionRegistry INSTANCE = new RegionRegistry();

    private final Map<ResourceLocation, RegionDefinition> pendingDefinitions = new ConcurrentHashMap<>();
    private final Object2ShortOpenHashMap<ResourceLocation> idToRawMap = new Object2ShortOpenHashMap<>();

    // Flat primitive-indexed arrays for O(1) Zero-GC lookups on worldgen and entity spawn hot paths
    private volatile RegionDefinition[] flatDefinitionsByRawId = new RegionDefinition[1];
    private volatile short[] weightedTicketTable = new short[]{FALLBACK_WILDS_ID};
    private volatile UniqueRegionQuadTree uniqueQuadTree = null;
    private volatile boolean hasUniqueRegions = false;
    private volatile boolean frozen = false;

    private volatile VoronoiEngine2D.GenerationMode generationMode = VoronoiEngine2D.GenerationMode.INFINITE_TESSELLATION;
    private volatile long maxFiniteRadiusSqQuart = (2500L * 2500L); // Default 10,000 blocks (2,500 QuartPos) radius squared

    private RegionRegistry() {
        this.idToRawMap.defaultReturnValue((short) -1);
        RegionDefinition fallback = new RegionDefinition(
                FALLBACK_WILDS_LOCATION,
                0,
                1,
                false,
                0,
                64,
                Optional.empty()
        );
        fallback.assignRawId(FALLBACK_WILDS_ID);
        this.pendingDefinitions.put(FALLBACK_WILDS_LOCATION, fallback);
        this.flatDefinitionsByRawId[0] = fallback;
        this.idToRawMap.put(FALLBACK_WILDS_LOCATION, FALLBACK_WILDS_ID);
    }

    public static RegionRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a {@link RegionDefinition} prior to server startup freeze.
     *
     * @param definition The region definition to register.
     */
    public synchronized void register(RegionDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("RegionDefinition cannot be null.");
        }
        this.pendingDefinitions.put(definition.id(), definition);
    }

    /**
     * Configures the world generation mode and finite centralized radius in block units.
     *
     * @param mode                  The {@link VoronoiEngine2D.GenerationMode} to use.
     * @param maxFiniteRadiusBlocks The maximum radius in blocks around {@code (0, 0)} when using {@code FINITE_CENTRALIZED}.
     */
    public void configureGenerationMode(VoronoiEngine2D.GenerationMode mode, int maxFiniteRadiusBlocks) {
        this.generationMode = mode;
        long radiusQuart = Math.max(16L, maxFiniteRadiusBlocks >> 2);
        this.maxFiniteRadiusSqQuart = radiusQuart * radiusQuart;
    }

    public VoronoiEngine2D.GenerationMode getGenerationMode() {
        return this.generationMode;
    }

    public long getMaxFiniteRadiusSqQuart() {
        return this.maxFiniteRadiusSqQuart;
    }

    /**
     * Freezes the registry on {@code ServerAboutToStartEvent}, resolving all {@code Holder<Biome>}
     * references directly and building flat O(1) lookup arrays and the unique region QuadTree.
     *
     * @param server The starting {@link MinecraftServer}.
     */
    public synchronized void freezeAndIndex(MinecraftServer server) {
        Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        ServerLevel overworld = server.overworld();
        long worldSeed = server.getWorldData().worldGenOptions().seed();
        UniqueRegionSavedData savedData = (overworld != null) ? UniqueRegionSavedData.getOrCreate(overworld) : null;

        // Sort non-fallback regions deterministically by ResourceLocation string
        List<RegionDefinition> sortedList = new ArrayList<>(this.pendingDefinitions.values());
        sortedList.removeIf(def -> FALLBACK_WILDS_LOCATION.equals(def.id()));
        sortedList.sort(Comparator.comparing(def -> def.id().toString()));

        int totalCount = sortedList.size() + 1;
        if (totalCount > Short.MAX_VALUE) {
            throw new IllegalStateException("RegionalAPI supports at most " + Short.MAX_VALUE + " regions.");
        }

        RegionDefinition[] newFlatArray = new RegionDefinition[totalCount];
        this.idToRawMap.clear();

        RegionDefinition fallback = this.pendingDefinitions.get(FALLBACK_WILDS_LOCATION);
        fallback.assignRawId(FALLBACK_WILDS_ID);
        if (fallback.biomeMaskRule() != null) {
            fallback.biomeMaskRule().freezeHolders(biomeRegistry);
        }
        newFlatArray[FALLBACK_WILDS_ID] = fallback;
        this.idToRawMap.put(FALLBACK_WILDS_LOCATION, FALLBACK_WILDS_ID);

        int totalTickets = 0;
        List<RegionDefinition> uniqueList = new ArrayList<>();

        for (int i = 0; i < sortedList.size(); i++) {
            short rawId = (short) (i + 1);
            RegionDefinition def = sortedList.get(i);
            def.assignRawId(rawId);
            if (def.biomeMaskRule() != null) {
                def.biomeMaskRule().freezeHolders(biomeRegistry);
            }
            newFlatArray[rawId] = def;
            this.idToRawMap.put(def.id(), rawId);

            if (def.isUnique()) {
                uniqueList.add(def);
            } else {
                totalTickets += def.selectionTicketWeight();
            }
        }

        // Build flat ticket lookup array for non-unique tessellating regions
        if (totalTickets <= 0) {
            this.weightedTicketTable = new short[]{FALLBACK_WILDS_ID};
        } else {
            short[] tickets = new short[totalTickets];
            int cursor = 0;
            for (int rawId = 1; rawId < newFlatArray.length; rawId++) {
                RegionDefinition def = newFlatArray[rawId];
                if (!def.isUnique()) {
                    int count = def.selectionTicketWeight();
                    for (int t = 0; t < count; t++) {
                        tickets[cursor++] = (short) rawId;
                    }
                }
            }
            this.weightedTicketTable = tickets;
        }

        // Pre-compute and index isUnique == true regions into the 2D QuadTree
        if (!uniqueList.isEmpty()) {
            UniqueRegionQuadTree quadTree = new UniqueRegionQuadTree(-1_000_000, -1_000_000, 1_000_000, 1_000_000);
            for (int i = 0; i < uniqueList.size(); i++) {
                RegionDefinition uniqueDef = uniqueList.get(i);
                long packedCenter = (savedData != null) ? savedData.getPackedQuartCenter(uniqueDef.id()) : Long.MIN_VALUE;
                int centerQX;
                int centerQZ;

                if (packedCenter != Long.MIN_VALUE) {
                    centerQX = (int) (packedCenter >> 32);
                    centerQZ = (int) packedCenter;
                } else {
                    // Deterministically compute anchor coordinates from worldSeed and region ID hash
                    long regionSeed = DeterministicHash.mixStafford13(worldSeed ^ uniqueDef.id().toString().hashCode());
                    int minRadiusQuart = uniqueDef.uniqueSearchMinRadiusBlocks() >> 2;
                    int maxRadiusQuart = uniqueDef.uniqueSearchMaxRadiusBlocks() >> 2;
                    int spanQuart = Math.max(1, maxRadiusQuart - minRadiusQuart);

                    int distanceQuart = minRadiusQuart + (int) (Long.remainderUnsigned(
                            DeterministicHash.mixStafford13(regionSeed + 0x9E3779B97F4A7C15L), spanQuart));

                    // Deterministic octant/quadrant placement without floating-point trig in hot path (only runs once at startup)
                    double angleRadians = ((DeterministicHash.mixStafford13(regionSeed + 0xBF58476D1CE4E5B9L) >>> 11) * 0x1.0p-53) * (Math.PI * 2.0D);
                    centerQX = (int) Math.round(Math.cos(angleRadians) * distanceQuart);
                    centerQZ = (int) Math.round(Math.sin(angleRadians) * distanceQuart);

                    if (savedData != null) {
                        savedData.putPackedQuartCenter(uniqueDef.id(), centerQX, centerQZ);
                    }
                }

                int influenceRadiusQuart = Math.max(32, (uniqueDef.weight() >> 2) + VoronoiEngine2D.DEFAULT_CELL_SIZE);
                long siteHash = DeterministicHash.hashGridCell(worldSeed, centerQX, centerQZ);
                long token = (siteHash & 0xFFFF_FFFF_FFFF_0000L) | (uniqueDef.rawId() & 0xFFFFL);

                quadTree.insert(
                        uniqueDef.rawId(),
                        centerQX,
                        centerQZ,
                        influenceRadiusQuart,
                        uniqueDef.weightSquaredQuart(),
                        token
                );
            }
            this.uniqueQuadTree = quadTree;
            this.hasUniqueRegions = true;
        } else {
            this.uniqueQuadTree = null;
            this.hasUniqueRegions = false;
        }

        this.flatDefinitionsByRawId = newFlatArray;
        this.frozen = true;

        LOGGER.info("RegionalAPI frozen {} region definitions ({} unique regions indexed in QuadTree).",
                newFlatArray.length, uniqueList.size());
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public int getEvaluatedRegionCount() {
        return this.flatDefinitionsByRawId.length;
    }

    /**
     * O(1) array-indexed lookup of a {@link RegionDefinition} by primitive {@code short} ID.
     * Zero allocations, safe for worldgen and entity spawn hot paths.
     *
     * @param rawId The primitive {@code short} region ID.
     * @return The corresponding {@link RegionDefinition}, or the fallback region if out of bounds.
     */
    public RegionDefinition getByRawId(short rawId) {
        RegionDefinition[] array = this.flatDefinitionsByRawId;
        if (rawId >= 0 && rawId < array.length) {
            return array[rawId];
        }
        return array[FALLBACK_WILDS_ID];
    }

    /**
     * Resolves the primitive {@code short} ID for a given {@link ResourceLocation}, or {@code -1} if unknown.
     */
    public short getRawId(ResourceLocation id) {
        return this.idToRawMap.getShort(id);
    }

    /**
     * Selects a non-unique region raw ID for a Voronoi cell using the pre-built ticket table.
     */
    public short selectRegionForCell(long cellHash, int gridX, int gridZ) {
        short[] table = this.weightedTicketTable;
        int len = table.length;
        if (len <= 1) {
            return table[0];
        }
        int selector = DeterministicHash.extractRegionSelector(cellHash);
        return table[selector % len];
    }

    /**
     * Queries the pre-computed {@link UniqueRegionQuadTree} if any unique regions exist.
     *
     * @return Winning unique region raw ID, or {@code -1} if none.
     */
    public short evaluateUniqueOverride(int quartX, int quartZ, long currentMinPowerDist) {
        if (!this.hasUniqueRegions) {
            return -1;
        }
        UniqueRegionQuadTree tree = this.uniqueQuadTree;
        return (tree != null) ? tree.queryDominantUniqueRegion(quartX, quartZ, currentMinPowerDist) : -1;
    }

    /**
     * Queries the pre-computed {@link UniqueRegionQuadTree} for the packed 64-bit site token.
     */
    public long evaluateUniqueOverrideToken(int quartX, int quartZ, long currentMinPowerDist) {
        if (!this.hasUniqueRegions) {
            return -1L;
        }
        UniqueRegionQuadTree tree = this.uniqueQuadTree;
        return (tree != null) ? tree.queryDominantUniqueToken(quartX, quartZ, currentMinPowerDist) : -1L;
    }

    /**
     * Queries the center block coordinates {@code ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL)}
     * for a unique region in under 0.05 ms.
     */
    public long findUniqueRegionBlockCenterPacked(short uniqueRegionRawId) {
        UniqueRegionQuadTree tree = this.uniqueQuadTree;
        if (tree == null) {
            return Long.MIN_VALUE;
        }
        return tree.findCenterBlockPosPacked(uniqueRegionRawId);
    }

    public Collection<RegionDefinition> getAllDefinitions() {
        return Collections.unmodifiableCollection(this.pendingDefinitions.values());
    }
}
