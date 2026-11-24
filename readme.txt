================================================================================
REGIONAL API (v1.0.11) - ARCHITECTURAL OVERVIEW & SYSTEM DOCUMENTATION
Target: Minecraft 1.21.1 (Java 21) | Loaders: NeoForge, Fabric, Forge (Mojmap)
================================================================================

1. OVERVIEW
--------------------------------------------------------------------------------
RegionalAPI is a high-performance, domain-agnostic geopolitical world partitioning
and 3D biome-masking engine for Minecraft 1.21.1. It divides the Minecraft world
into distinct, deterministic 2D territories ("Regions") aligned strictly to 16x16
chunk boundaries (1 Chunk = Maximum 1 Region) without altering Vanilla terrain
heightmaps or overriding the Vanilla world seed.

RegionalAPI is designed as a pure core library engine following a strict One-Way
Addon / Bridge Architecture:
- RegionalAPI (Core Engine): Handles spatial Laguerre-Voronoi math, salted seed
  derivation, 3-tier Zero-GC chunk-level region caching, 3D vertical biome
  masking, `/locate region <region> [biome|structure]` command integration, F3 HUD display, and cross-loader
  regional events (`PlayerEnterRegion`, `EntitySpawnInRegion`).
- Consumer Addons / Bridges (e.g., RegionalCobblemonBridge, MedieavalRegional,
  RegionalXaero): Register custom `RegionDefinition` instances and hook into
  RegionalAPI to enforce gameplay rules (such as regional Pokemon spawning,
  regional evolutions, structure restrictions, or map overlays).


2. HOW WORLD SEEDS WORK (NON-DESTRUCTIVE & READ-ONLY)
--------------------------------------------------------------------------------
RegionalAPI NEVER overrides, replaces, or mutates Minecraft's native world seed.

- Native Seed Preservation:
  Minecraft's original world seed (`WorldGenOptions#seed()`) remains 100% intact.
  Terrain generation (mountains, rivers, caves, ore distribution, and Vanilla
  structure grid placement) continues to use Minecraft's native seed.

- Deterministic Salted Seed Derivation & Authoritative Locking:
  Before world loading (`ServerAboutToStartEvent` on NeoForge/Forge,
  `ServerLifecycleEvents.SERVER_STARTING` on Fabric), RegionalAPI reads the
  native world seed and computes an isolated, deterministic 64-bit salted SHA-256
  regional seed (`ClientboundSyncRegionalSeedPacket.computeSaltedRegionalSeed`),
  locking it via `ThreadLocalRegionCache.setAuthoritativeRegionalSeed(...)`.
  This guarantees that two worlds created with the same Minecraft seed always
  generate the exact same RegionalAPI territorial map while preventing third-party
  structure generators from clobbering the active regional seed mid-session.


3. 2D GEOPOLITICAL WORLD PARTITIONING ENGINE (1 CHUNK = MAX 1 REGION)
--------------------------------------------------------------------------------
RegionalAPI partitions the world horizontally (X, Z) using a 2D Laguerre-Voronoi
(Power Diagram) engine (`VoronoiEngine2D`):

- Strict Single-Region Per Chunk Guarantee (`v1.0.9+`):
  Every 16x16 block chunk is guaranteed 100% homogeneous (`isHomogeneous == true`)
  and contains at most 1 region. Horizontal Voronoi evaluation is quantized to
  the canonical center `QuartPos` of each chunk (`(chunkX << 2) + 2,
  (chunkZ << 2) + 2`, i.e., block offset `(+8, +8)`). Region transitions occur
  strictly along 16x16 chunk boundaries—eliminating mid-chunk region splitting,
  F3 flicker, or split chunk biome overrides.

- Laguerre-Voronoi (Power Diagram) Cells & Dynamic Cell Scale:
  Dominance is resolved purely in 64-bit integer arithmetic:
    Power(P, Si) = (x - xi)^2 + (z - zi)^2 - wi^2
  Each `RegionDefinition` defines both a territorial radius `weight` (in blocks)
  and a `selectionTicketWeight` (relative cell frequency). The Voronoi grid cell
  shift (`voronoiCellShift`, range 7..13, corresponding to 512..32,768 blocks
  per cell) scales automatically from the largest registered region weight or can
  be configured manually in `config/regionalapi.json`.

- Two World Partitioning Modes (`VoronoiEngine2D.GenerationMode`):
  1. `INFINITE_TESSELLATION` (Default):
     Regions repeat organically across the infinite Minecraft world according to
     their `weight` and `selectionTicketWeight`.
  2. `FINITE_CENTRALIZED`:
     Locks registered regions within `maxFiniteRadiusBlocks` around `(0, 0)` and
     wraps all exterior chunks in the fallback Wilds territory
     (`RegionRegistry.FALLBACK_WILDS_ID = 0`, `"regionalapi:wilds"`).

- Unique Regions (`UniqueRegionQuadTree` & `UniqueRegionSavedData`):
  Regions with `isUnique == true` are excluded from repeating Voronoi cells and
  placed deterministically once within `[uniqueSearchMinRadiusBlocks,
  uniqueSearchMaxRadiusBlocks]` around `(0, 0)`. They are indexed in a lock-free
  2D QuadTree (resolving dominance and `/locate region` queries in < 0.05 ms) and
  persisted in Overworld `DimensionDataStorage` (`regionalapi_unique_regions.dat`).


4. OPTIONAL 3D VERTICAL BIOME MASKING (`BiomeMaskRule`)
--------------------------------------------------------------------------------
Any `RegionDefinition` can optionally attach a `BiomeMaskRule` to override
surface biomes, cave biomes, or both along the vertical Y-axis without world or
block lookups (100% thread-safe during concurrent worldgen):

- `VerticalMode.STATIC_Y_THRESHOLD`:
  Pure integer comparison on `quartY` (`blockY >> 2 > surfaceQuartYThreshold`),
  completely bypassing `Climate.Sampler` 3D density evaluation and enabling
  Whole-Section `PalettedContainer` Short-Circuiting in `MixinLevelChunkSection`.
- `VerticalMode.TERRAIN_FOLLOWING_DEPTH`:
  Evaluates only `sampler.depth()` (`depth > 0.0D` = underground cave even inside
  tall mountains; `depth <= 0.0D` = surface), skipping the other 5 climate channels.


5. 3-TIER ZERO-GC CACHING & COMPACT 3-BYTE NBT ARCHITECTURE
--------------------------------------------------------------------------------
Because biome sampling, structure checks, and entity spawners query regions
thousands of times per second, RegionalAPI uses a 3-Tier Zero-GC Caching Pipeline:

- Tier 1: Server-Thread FastUtil Cache (`RegionalApi.serverHomogeneousChunkCache`)
  Maps `ChunkPos.asLong(chunkX, chunkZ)` to homogeneous `short` region IDs in a
  `Long2ShortOpenHashMap` for instant O(1) lookups on the main server thread.

- Tier 2: Loaded Chunk Duck Interface (`IRegionChunkData` on `ChunkAccess`)
  Injected directly into `ChunkAccess` via `MixinChunkAccess`, propagated from
  `ProtoChunk` to `LevelChunk` via `MixinLevelChunk`, validated in O(1) against
  `ThreadLocalRegionCache.getCurrentEpoch()`, and persisted in chunk NBT
  (`"regionalapi:region_data"`) via `MixinChunkSerializer` in just 3 bytes per
  chunk (1 byte homogeneous flag + 1 big-endian `short` region ID). Legacy
  33-byte border tags from older worlds are automatically upgraded on read.

- Tier 3: Worldgen Worker Ring Buffer (`ThreadLocalRegionCache`)
  During concurrent world generation (where `BiomeSource.getNoiseBiome` runs
  1,536 times per chunk without a `ChunkAccess` reference), each worker thread
  uses a lock-free 32-slot direct-mapped ring buffer so the chunk's canonical
  center Voronoi evaluation executes only once per chunk.


6. CURRENT CAPABILITIES SUMMARY (v1.0.11)
--------------------------------------------------------------------------------
- Built-in in RegionalAPI Core (`com.regionalapi`):
  * Chunk-Homogeneous 2D Laguerre-Voronoi + 2D QuadTree Region Partitioning Engine
    (Strict 1 Chunk = Max 1 Region guarantee)
  * Deterministic Two-Phase Server Lifecycle (`freezeAndIndex` before world load,
    `syncUniqueRegionsWithOverworld` after Overworld initialization)
  * 3D Vertical Biome Masking (`BiomeMaskRule`: `STATIC_Y_THRESHOLD` &
    `TERRAIN_FOLLOWING_DEPTH`, Surface-Only, Cave-Only, or Dual Override)
  * Optional Declarative Region Content Metadata & Introspection (`RegionContentRegistry`):
    Addons can register region-bound biomes, structures, and tags via `RegionalApi`
    (`registerRegionBiome*`, `registerRegionStructure*`) and query them from any
    third-party mod (`getRegisteredRegionBiomes`, `getRegisteredRegionStructures`,
    `isBiomeRegisteredForRegion`, `isStructureRegisteredForRegion`), with automatic
    inclusion of `BiomeMaskRule` overrides.
  * Sub-Microsecond O(1) Spatial Lookup API (`getRegionRawId`, `getRegionId`,
    `getRegionDefinition`, `getRegionByRawId`, `getRawIdForLocation`,
    `getUniqueRegionCenterBlockPacked`)
  * Clientbound Salted Seed & Player Region Synchronization Packets
  * Cross-Loader Region Events (`PlayerEnterRegionEvent`, `EntitySpawnInRegionEvent`,
    `FabricRegionalEvents`) across NeoForge, Fabric, and Forge
  * Vanilla `/locate region` Command Suite & F3 Debug Overlay (`Region: <namespace:path>`):
    - `/locate region <region_id>`
    - `/locate region <region_id> biome <biome_id | #biome_tag>`
    - `/locate region <region_id> structure <structure_id | #structure_tag>`
    Powered by `RegionSpatialLocator` with O(1) region pre-filtering, 3D
    `BiomeMaskRule` awareness, async worker offloading (`Util.backgroundExecutor()`)
    for biomes, vanilla-exact ring-by-ring (`r = 0 .. 100`) `StructureManager` /
    `ChunkStatus.STRUCTURE_STARTS` verification for structures (zero artificial
    candidate/time truncation), and region-scoped server-authoritative
    tab-completion (automatically scoped for dual `BiomeMaskRule` and explicitly
    registered region biomes/structures, falling back to global registries otherwise).

- Handled via Addon / Bridge Layer (e.g., RegionalCobblemonBridge, MedieavalRegional):
  * Domain-specific Regional Spawning, Structure Gatekeeping, & Gameplay Rules
================================================================================
