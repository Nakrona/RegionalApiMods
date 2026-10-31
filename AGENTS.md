# AGENTS.md - Multi-Loader Architecture & Development Guidelines

This document serves as the authoritative guide for AI coding assistants and human developers working within the **RegionalAPI** repository.

---

## 1. Project Overview & Architecture

**RegionalAPI** (`regionalapi`) is a high-performance procedural 2D Laguerre-Voronoi regional partitioning and 3D biome-override engine for **Minecraft 1.21.1 (Java 21)**.

The project uses a single-repository Multi-Loader layout:
- **`:common`**: Pure vanilla Minecraft (Mojang Mappings / Mojmap) and SpongePowered Mixin. Zero loader-specific imports. Contains the core Voronoi engine, 2D QuadTree spatial index, thread-local zero-GC caches, 3D biome-override mixins, network packets, and Duck Interface chunk data abstractions.
- **`:neoforge`**: NeoForge loader implementation (`net.neoforged.moddev`). Implements Java SPI `IPlatformHelper`, NeoForge mod lifecycle (`@Mod`), NeoForge Event Bus integration (`RegionGameplayListeners`), and exposes `com.regionalapi.api.RegionEvent` for 100% backward compatibility with downstream NeoForge mods (e.g., `MedieavalRegional`).
- **`:fabric`**: Fabric loader implementation (`fabric-loom`). Implements Java SPI `IPlatformHelper`, Fabric entrypoints (`ModInitializer`, `ClientModInitializer`), Fabric API networking (`PayloadTypeRegistry`, `ServerPlayNetworking`, `ClientPlayNetworking`), and Fabric API event callbacks (`FabricRegionalEvents`).

---

## 2. Directory Structure

```
D:\RegionalAPI\RegionalApiMods\
├── AGENTS.md                          # This architecture and guidelines file
├── build.gradle                       # Root orchestration & artifact distribution
├── gradle.properties                  # Dependency versions & mod metadata
├── settings.gradle                    # Subproject definitions (:common, :neoforge, :fabric)
│
├── common\                            # Shared vanilla + mixin layer
│   └── src\main\
│       ├── java\com\regionalapi\
│       │   ├── RegionalModConstants.java
│       │   ├── api\
│       │   │   ├── RegionalApi.java   # Developer facade (Zero-GC queries)
│       │   │   └── event\             # Universal functional callbacks
│       │   ├── core\
│       │   │   ├── cache\             # IRegionChunkData, ThreadLocalRegionCache
│       │   │   ├── math\              # VoronoiEngine2D, DeterministicHash
│       │   │   └── region\            # RegionRegistry, QuadTree, BiomeMask
│       │   ├── mixin\                 # Chunk, BiomeSource, and Player Mixins
│       │   ├── network\               # ClientboundSyncRegionalSeedPacket
│       │   └── platform\              # IPlatformHelper (SPI), CommonRegionMovementTracker
│       └── resources\
│           └── regionalapi.mixins.json
│
├── neoforge\                          # NeoForge loader module
│   └── src\main\
│       ├── java\com\regionalapi\
│       │   ├── api\RegionEvent.java   # NeoForge event bus compatibility
│       │   └── neoforge\              # NeoForgePlatformHelper, RegionalNeoForgeMod
│       └── resources\
│           └── META-INF\
│               ├── neoforge.mods.toml
│               └── services\com.regionalapi.platform.IPlatformHelper
│
└── fabric\                            # Fabric loader module
    └── src\main\
        ├── java\com\regionalapi\fabric\
        │   ├── FabricPlatformHelper.java
        │   ├── FabricRegionalEvents.java
        │   ├── RegionalFabricMod.java
        │   └── RegionalFabricClientMod.java
        └── resources\
            ├── fabric.mod.json
            └── META-INF\services\com.regionalapi.platform.IPlatformHelper
```

---

## 3. Strict Rules & Architectural Constraints

### 3.1. 100% English Code Policy
- Every single codebase artifact—class names, method names, variable names, parameters, Javadoc, inline comments (`//`, `/* */`), log messages, and resource keys—MUST be written strictly in English.

### 3.2. Zero External Wrapper Libraries
- **Do NOT introduce Architectury API or similar external multi-loader abstraction layers.**
- RegionalAPI is a core foundational library mod. It must remain lightweight with zero unnecessary transitive dependencies.
- Cross-platform abstraction is achieved exclusively through:
  1. Standard Java `ServiceLoader` (SPI) via `IPlatformHelper`.
  2. Pure Vanilla Mojang Mappings (Mojmap).
  3. Bundled `it.unimi.dsi.fastutil.*` primitive collections.
  4. SpongePowered Mixin.

### 3.3. Duck Interface Over Platform Attachments
- Do not use platform-specific attachment APIs (such as NeoForge `AttachmentType`) for chunk or player regional data.
- **`IRegionChunkData`**: Injected directly into `net.minecraft.world.level.chunk.ChunkAccess` via `MixinChunkAccess`. Serialized directly to chunk NBT (`regionalapi:region_data`) via `MixinChunkSerializer` with 3 bytes (homogeneous) or 33 bytes (border).
- **`IPlayerRegionTracker`**: Injected directly into `net.minecraft.server.level.ServerPlayer` via `MixinServerPlayer` with zero object allocation.

### 3.4. Strict Code Mirroring & Synchronization
When adding or modifying features:
1. **Common First**: Place all mathematical algorithms, caching mechanisms, spatial quadtrees, biome overrides, and packet structures in `:common`.
2. **Platform Parity**: Whenever a new platform capability is added to `IPlatformHelper`, implement it simultaneously in both `NeoForgePlatformHelper` and `FabricPlatformHelper`.
3. **Backward Compatibility**: Preserve existing signatures in `com.regionalapi.api.RegionalApi` and `com.regionalapi.api.RegionEvent` so downstream mods like `MedieavalRegional` continue functioning without recompilation or source changes.

---

## 4. Build & Distribution

To build both loaders and copy the distribution JARs:
```bash
./gradlew copyBuildJars
```
The output artifacts are saved to `D:\RegionalAPI\BuildVersion\`:
- `regionalapi-neoforge-1.0.0.jar`
- `regionalapi-fabric-1.0.0.jar`
