# RegionalAPI (`v1.0.11`)

**High-Performance Procedural 2D Laguerre-Voronoi Geopolitical World Partitioning & 3D Biome-Masking Engine for Minecraft 1.21.1**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen.svg)]()
[![Java](https://img.shields.io/badge/Java-21-orange.svg)]()
[![Loaders](https://img.shields.io/badge/Loaders-NeoForge%20%7C%20Fabric%20%7C%20Forge-blue.svg)]()
[![License](https://img.shields.io/badge/License-LGPL--3.0-lightgrey.svg)]()

---

## Overview

**RegionalAPI** (`mod_id: regionalapi`, package: `com.regionalapi`) is a pure core library mod for **Minecraft 1.21.1 (Java 21)** across **NeoForge, Fabric, and Forge** (Official Mojang Mappings). It partitions the Minecraft world into massive, deterministic, invisible 2D territories (**Regions**) aligned strictly to `16x16` chunk boundaries (**1 Chunk = Maximum 1 Region**) without altering vanilla terrain heightmaps or mutating the native world seed.

Designed around a **One-Way Addon / Bridge Architecture**, `RegionalAPI` provides the spatial math, 3-tier Zero-GC chunk caching, 3D vertical biome masking, optional declarative region biome/structure metadata & introspection (`RegionContentRegistry`), region-constrained `/locate region` command integration with region-scoped tab-completion, F3 debug overlay, and cross-loader events so downstream mods (such as `RegionalCobblemonBridge`, `MedieavalRegional`, or RPG/map addons) can bind spawns, structures, and gameplay rules to geopolitical territories in sub-microsecond time.

---

## Key Architectural Pillars (`v1.0.11`)

1. **Strict Single-Region Per Chunk Guarantee (`1 Chunk = Max 1 Region`)**:
   - Every `16x16` chunk is guaranteed `100%` homogeneous (`isHomogeneous == true`) and belongs to at most 1 region.
   - Horizontal Laguerre-Voronoi evaluation is quantized to the canonical center `QuartPos` of each chunk (`(chunkX << 2) + 2, (chunkZ << 2) + 2`, i.e., block offset `(+8, +8)`), eliminating mid-chunk region splitting, F3 flicker, and split chunk biome overrides.
2. **Weighted 2D Laguerre-Voronoi Tessellation & Dynamic Cell Scaling**:
   - Evaluates squared power distance $\text{Power}(P, S_i) = (x - x_i)^2 + (z - z_i)^2 - w_i^2$ purely in 64-bit integer arithmetic without `Math.sqrt` or floating-point division.
   - Supports `INFINITE_TESSELLATION` (endless weighted repeating regions) and `FINITE_CENTRALIZED` (bounded continent around `(0, 0)` wrapped in `regionalapi:wilds`, ID `0`).
   - Automatically scales Voronoi grid spacing (`voronoiCellShift` `7..13`, i.e., `512` to `32,768` blocks per cell) from the maximum registered region weight.
3. **Unique Regions & Lock-Free 2D QuadTree (`isUnique == true`)**:
   - One-of-a-kind sanctuary/boss regions are deterministically anchored once within `[uniqueSearchMinRadiusBlocks, uniqueSearchMaxRadiusBlocks]`, indexed in `UniqueRegionQuadTree` (`< 0.05 ms` lookup), and persisted in Overworld `DimensionDataStorage` (`regionalapi_unique_regions.dat`).
4. **Optional 3D Vertical Biome Masking (`BiomeMaskRule`)**:
   - Selectively overrides surface biomes, cave biomes, or both along the vertical Y-axis using `VerticalMode.STATIC_Y_THRESHOLD` (with whole-section `PalettedContainer` short-circuiting in `MixinLevelChunkSection`) or `VerticalMode.TERRAIN_FOLLOWING_DEPTH` (`sampler.depth()`).
5. **3-Tier Zero-GC Caching & 3-Byte NBT Persistence**:
   - **Tier 1**: Server-thread `Long2ShortOpenHashMap` (`serverHomogeneousChunkCache`).
   - **Tier 2**: `IRegionChunkData` Duck Interface injected into `ChunkAccess` (`MixinChunkAccess`), validated in O(1) via `regional$populatedEpoch`, and saved in chunk NBT (`MixinChunkSerializer`) in just **3 bytes per chunk**.
   - **Tier 3**: Lock-free 32-slot per-thread `ThreadLocalRegionCache` ring buffer for concurrent worldgen worker threads.
6. **Built-in `/locate region` Command Suite, F3 Overlay, & Multi-Loader Events**:
   - Native vanilla `/locate` integration supporting:
     - `/locate region <region_id>` — locates the nearest matching region (instant QuadTree lookup for unique regions, Voronoi ring search for repeating regions).
     - `/locate region <region_id> biome <biome_id | #biome_tag>` — locates the nearest biome (or biome tag) strictly inside the specified region, honoring 3D `BiomeMaskRule` overrides with async worker offloading (`Util.backgroundExecutor()`).
     - `/locate region <region_id> structure <structure_id | #structure_tag>` — locates the nearest structure (or structure tag) strictly inside the specified region using O(1) chunk-homogeneous pre-filtering and vanilla-exact ring-by-ring (`r = 0 .. 100`) `StructureManager` / `ChunkStatus.STRUCTURE_STARTS` verification with zero artificial candidate or time truncation.
   - **Region-Scoped Server-Authoritative Tab-Completion & Inter-Addon Introspection (`RegionContentRegistry`)**:
     - Regions using a dual `BiomeMaskRule` automatically filter `/locate region <region> biome` suggestions to their masked biomes.
     - Addons may optionally declare region-bound biomes, structures, and `#tags` via `RegionalApi` (`registerRegionBiomes`, `registerRegionStructures`, `registerRegionBiomeTag`, `registerRegionStructureTag`) so third-party mods can introspect them (`getRegisteredRegionBiomes`, `getRegisteredRegionStructures`, `isStructureRegisteredForRegion`) and `/locate region <region> [biome|structure]` automatically suggests only that region's registered entries (falling back to global Minecraft registries when none are declared).
   - Displays `Region: <namespace:path>` directly above `Biome:` on the F3 debug screen.
   - Fires `PlayerEnterRegion` and `EntitySpawnInRegion` across NeoForge, Fabric, and Forge.

---

## Commands

```text
/locate region <region_id>
/locate region <region_id> biome <biome_id | #biome_tag>
/locate region <region_id> structure <structure_id | #structure_tag>
```

---

## Documentation & Design Reference

- **[RPD & Base Code Design Document](file:///X:/MinecraftProject/RegionalAPI/RegionalApiMods/RPD_DESIGN_AND_BASE_CODE.md)** — Full product requirements, Mermaid architecture diagrams, package index, and base code specifications.
- **[Plain-Text Architectural Overview (`readme.txt`)](file:///X:/MinecraftProject/RegionalAPI/RegionalApiMods/readme.txt)** — System overview and caching hierarchy.
- **[Changelog (`Logupdate/changelog.txt`)](file:///X:/MinecraftProject/RegionalAPI/RegionalApiMods/Logupdate/changelog.txt)** — Complete version history from `v1.0.0` to `v1.0.11`.

---

## Quick Start for Addon Developers

Register regions in your `:common` module during mod initialization (before `ServerAboutToStartEvent` / `SERVER_STARTING` freezes the registry):

```java
RegionalApi api = RegionalApi.getInstance();

api.configureGenerationMode(VoronoiEngine2D.GenerationMode.INFINITE_TESSELLATION, 15000);
api.setVoronoiCellShift(0); // Auto-compute cell scale from max region weight

api.registerRegion(new RegionDefinition(
        ResourceLocation.fromNamespaceAndPath("myaddon", "kanto"),
        2048, // Laguerre weight (radius in blocks)
        15,   // Selection ticket weight (relative frequency)
        false,
        0,
        64,
        Optional.empty()
));
```

Query the active region in sub-microsecond O(1) time without allocating `BlockPos`:

```java
short rawId = RegionalApi.getInstance().getRegionRawId(serverLevel, blockX, blockZ);
ResourceLocation regionId = RegionalApi.getInstance().getRegionId(serverLevel, blockX, blockZ);
RegionDefinition def = RegionalApi.getInstance().getRegionDefinition(serverLevel, blockX, blockZ);
```

Optionally declare region-bound structures/biomes so other mods can introspect them and `/locate region <region> [biome|structure]` tab-completion automatically scopes suggestions to that region:

```java
ResourceLocation alola = ResourceLocation.fromNamespaceAndPath("myaddon", "alola");
api.registerRegionStructure(alola, ResourceLocation.fromNamespaceAndPath("myaddon", "alola_trial"));
api.registerRegionBiome(alola, ResourceLocation.fromNamespaceAndPath("minecraft", "jungle"));

// Any other mod can query registered region content without depending on your addon:
Set<ResourceLocation> alolaStructures = api.getRegisteredRegionStructures(alola);
Set<ResourceLocation> alolaBiomes = api.getRegisteredRegionBiomes(alola);
```
