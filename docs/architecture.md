# Architecture

How Realistic Landforms is laid out and how it runs: the modules and packages, what each part is for, and how a world and its chunks are generated. The diagrams are UML in Mermaid, which GitHub renders in place. For the design ideas behind the terrain, see [How it works](how-it-works.md); for building and tests, see [Building and development](development.md).

## Code structure

```
core/                       Terrain logic in plain Java, shared by every version (no Minecraft classes)
  logic/                    The pipeline: layers, regions, caches, settings
    debug/                  Height map renderer for debug maps
  noise/                    Stateless deterministic noise: hashing, value noise, fBm, ridged noise
  preview/                  Standalone map viewer (java -jar <mod jar>)
  src/test/                 Unit tests of the logic
versions/<minecraft>/       One set of modules per Minecraft version (1.20.1, 26.3; 26.4 prepared)
  common/                   Connects the logic to that version's vanilla generator
    world/                  Density functions, biome source, water, surface, caves, ores, config
    mixin/                  Hooks into vanilla's generator, world creation, saving and weather
    command/                /mwg debug commands
    gametest/               In-game tests, run on every loader
    benchmark/              Chunk generation benchmark against vanilla
    platform/               Interface for the few things that differ between loaders
  fabric/ forge/ neoforge/  Registration and the platform implementation of one loader each
amidst/                     Amidst map viewer for MTM worlds (GPLv3, separate project)
docs/                       How it works, development, this document
```

The rule that shapes everything: **all terrain is decided in `core`**, as pure functions of the seed, the coordinates and the settings. Version modules only hand those results to vanilla, and loader modules only register things. This keeps terrain identical on every loader and Minecraft version, and in any exploration order.

```mermaid
flowchart LR
    subgraph Loader["fabric / forge / neoforge"]
        Entry["MtmWorldgenFabric / Forge / NeoForge<br/>registers codecs, commands"]
        PlatformImpl["FabricPlatform / ForgePlatform / NeoForgePlatform"]
    end
    subgraph Common["versions/&lt,mc&gt,/common"]
        World["world: TerrainDensity, TerrainClimate, TerrainCaves,<br/>MtmBiomeSource, MtmWater, TerrainCondition, ..."]
        Mixins["mixin: hooks into vanilla"]
        Commands["command: /mwg"]
        Platform["platform.Platform"]
    end
    subgraph Core["core"]
        Logic["logic: TerrainLogic and its layers"]
        Noise["noise"]
        Preview["preview"]
    end
    Vanilla[("Vanilla Minecraft<br/>chunk generator")]
    Entry --> World
    PlatformImpl -.implements.-> Platform
    Mixins --> World
    Vanilla <--> Mixins
    Vanilla --> World
    World --> Logic
    Commands --> Logic
    Logic --> Noise
    Preview --> Logic
```

## The terrain logic (`core/logic`)

`TerrainLogic` is the one entry point. A world gets one instance per seed and settings (`TerrainLogic.forWorld`), and every question about the world goes to it: surface height at a column, water level, biome, whether a block is solid, where the spawn is.

It is built from layers that follow the generation pipeline. Each layer is a pure function of the seed and coordinates, or of the layers before it.

| Class | Pipeline step | What it does |
|---|---|---|
| `ContinentLayer` | 1 | Jittered Voronoi plates grouped into continents, coastlines, islands. Plate drift and character, and mountain belts along plate borders (`belt`) |
| `ClimateLayer` | 2 | Temperature zones and humidity |
| `SkeletonLayer` | 3 | Tier field (plains, hills, mountains), mountain ranges, plateaus, hills, isolated peaks, cliff bands |
| `ErosionLayer` | 3 | Stream power erosion simulation per region; branching gullies and creeks |
| `FlowLayer` → `FlowRegion` | 4 | Priority flood routing on a 256 block grid, catchments, river classes, lakes and sinks |
| `LakeLayer` | 5 | Lake shapes and levels on the real ground |
| `RiverLayer` → `RiverRegion` | 4 | River courses as curves, widths, levels, waterfalls, fans, oxbows, deltas, sediment bars |
| `CoastLayer`, `SeaStackLayer` | 7 | Coast types, sea cliffs, sea stacks |
| `BiomeLayer` | 8 | Biome lists and patches by zone and humidity; cave biomes and the deep dark |
| `SinkholeLayer`, `OverhangLayer`, `DesertLayer` | 3, 9 | Sinkholes and their chambers, overhangs on cliffs, dunes and oases |
| `OreHeights` | 10 | Ore height ranges stretched to the taller world |
| `ForeignMap` | | Snapshot of region files merged in from other worlds, and blending toward them |

Support classes:

| Class | What it does |
|---|---|
| `WorldgenSettings` | Every setting, grouped as in the config file; JSON in and out, validation, `resolved()` for `logicRange` |
| `LogicRegion` | Coarse fields of one region (continent height, coast distance) on the 64 block grid |
| `RegionCache` | LRU cache of computed regions: computes each region once, lets threads read without locks, and can hold regions in use |
| `LogicThreads` | Small fork-join pool for the expensive steps, safe because they are pure functions |
| `ErosionStore` | Disk cache of erosion results under `<world>/mtm_worldgen/erosion/` |
| `NoiseStats` | Quantiles of a noise field, which turn shares (30% ocean, 33% plains) into thresholds |

```mermaid
classDiagram
    direction LR
    class TerrainLogic {
        +forWorld(seed, settings)$ TerrainLogic
        +solidHeight(x, y, z) double
        +surfaceHeight(x, z) double
        +sampleSurfaceHeight(x, z) double
        +sampleWaterLevel(x, z) double
        +sampleRiver(x, z) RiverRegion.Hit
        +sampleBiome(x, z) String
        +highestBlockInChunk(chunkX, chunkZ) int
        +findSpawn() BlockColumn
        +setForeign(ForeignMap)
        +useCacheDirectory(Path)
    }
    class WorldgenSettings {
        +fromJson(String)$
        +toJson() String
        +resolved() WorldgenSettings
    }
    class RegionCache~T~ {
        +get(regionX, regionZ) T
        +hold(regionX, regionZ) T
        +release(regionX, regionZ)
    }
    class ContinentLayer {
        +value(x, z) double
        +belt(x, z, width, wide) double[]
        +coastDistance(x, z) double
    }
    class SkeletonLayer {
        +height(x, z, base, texture, ...) double
        +uplandShare(x, z, base) double
        +landform(x, z, base) Landform
    }
    class ErosionLayer {
        +change(x, z, warpShare) double
        +creek(x, z, warpShare) Creek
    }
    class FlowLayer {
        +compute(regionX, regionZ) FlowRegion
    }
    class LakeLayer {
        +compute(regionX, regionZ) LakeRegion
    }
    class RiverLayer {
        +compute(regionX, regionZ) RiverRegion
    }
    class RiverRegion {
        +nearest(x, z) Hit
        +lowered(ground, lake) RiverRegion
    }
    class ErosionStore
    class ClimateLayer
    class BiomeLayer
    class CoastLayer
    class DesertLayer
    class SinkholeLayer
    class OverhangLayer
    class SeaStackLayer
    class ForeignMap

    TerrainLogic --> WorldgenSettings
    TerrainLogic *-- ContinentLayer
    TerrainLogic *-- ClimateLayer
    TerrainLogic *-- SkeletonLayer
    TerrainLogic *-- ErosionLayer
    TerrainLogic *-- BiomeLayer
    TerrainLogic *-- CoastLayer
    TerrainLogic *-- DesertLayer
    TerrainLogic *-- SinkholeLayer
    TerrainLogic *-- OverhangLayer
    TerrainLogic *-- SeaStackLayer
    TerrainLogic o-- ForeignMap
    TerrainLogic *-- "6" RegionCache : region caches
    RegionCache ..> FlowLayer : computes with
    RegionCache ..> LakeLayer : computes with
    RegionCache ..> RiverLayer : computes with
    RiverLayer ..> RiverRegion : builds
    SkeletonLayer --> ContinentLayer
    ErosionLayer --> ErosionStore
```

### Regions and how they depend on each other

Large-scale logic is computed per logic region (4096 × 4096 blocks by default) and kept in a `RegionCache`. A region is only a cache unit, not a boundary: every rule depends on the seed, the coordinates and a bounded neighborhood, so neighboring regions agree. A region of one kind may need regions of another kind around it, always in this order:

```mermaid
flowchart TD
    LR["LogicRegion<br/>coarse fields, 64 block grid"]
    FR["FlowRegion<br/>routing, catchments, lakes, sinks<br/>window: region ± flowLookAhead"]
    LK["LakeRegion<br/>lake shapes on smooth ground"]
    NR["natural RiverRegion<br/>courses as the seed lays them out"]
    RR["RiverRegion<br/>with foreign chunks cutting rivers off"]
    ER["Eroded region<br/>erosion on a 16 block grid<br/>window: region ± erosion.margin"]
    FT["fitted RiverRegion<br/>water lowered onto eroded ground,<br/>held up to lakes"]
    DISK[("ErosionStore<br/>disk cache")]
    LR --> FR
    FR --> LK
    FR --> NR
    FR --> RR
    LK --> ER
    NR --> ER
    LR --> ER
    ER -. read and write .-> DISK
    RR --> FT
    ER --> FT
    LK --> FT
```

Erosion is the costliest step (about half a second to a second and a half per region). Its results go to disk, so a restart only recomputes the cheap steps. While generating, the neighbors of an eroded region are queued on a background thread, so they are usually ready by the time the player gets there.

```mermaid
sequenceDiagram
    participant Caller as Chunk worker thread
    participant Cache as RegionCache
    participant Store as ErosionStore
    participant Pool as LogicThreads
    participant Prefetch as Prefetch thread
    Caller->>Cache: get(region)
    alt cached in memory
        Cache-->>Caller: region
    else not cached
        Cache->>Cache: hold the regions it needs (terrain, flow, lakes, rivers)
        Cache->>Store: read(region)
        alt on disk, key matches
            Store-->>Cache: eroded heights and creeks
        else missing or stale
            Cache->>Pool: forEach(rows) of each erosion round
            Pool-->>Cache: eroded heights and creeks
            Cache->>Store: write(region)
        end
        Cache->>Cache: release held regions
        Cache-->>Caller: region
        Cache-)Prefetch: queue the 8 neighbors
    end
```

### How one column gets its height

Every column's surface is assembled in stages, from the largest scale to the smallest. Each stage only adds to or shapes the result of the ones before it:

```mermaid
flowchart TD
    A["Continent base<br/>ContinentLayer: plates, coasts"] --> B
    B["Skeleton<br/>SkeletonLayer: tier field from plates and belts,<br/>ranges, plateaus, hills, peaks"] --> C
    C["Erosion change<br/>ErosionLayer: gullies, spurs, cliffs stepped"] --> D
    D["Sea cliffs<br/>CoastLayer"] --> E
    E["Rivers<br/>valley walls, floodplains, terraces, bars"] --> F
    F["Fans and oxbow lakes"] --> G
    G["Lakes<br/>basin, rim, inland sinks"] --> H
    H["Dunes and oases<br/>DesertLayer"] --> I
    I["Creeks<br/>small valleys from erosion"] --> J
    J["Sea stacks, sinkholes"] --> K
    K["Blend toward foreign chunks<br/>ForeignMap"] --> L
    L(["Surface height"])
```

In code this is `sampleSurfaceHeight`, which calls `sampleShapedLand` (creeks), `sampleCarvedLand` (skeleton, erosion and `carve`), and wraps them with sea stacks, sinkholes and foreign blending. Blocks above the height map, such as overhangs and sinkhole chambers, come from `solidHeight`, which density asks for. Results are memoized per column and thread (`ColumnCache`), because one column is asked many times.

## Connecting to Minecraft (`versions/<mc>/common`)

The mod keeps vanilla's chunk generator and replaces what it reads:

| Class | Role |
|---|---|
| `MtmGenerators` | Builds the MTM generator: vanilla `NoiseBasedChunkGenerator` with MTM density functions in its noise router, and the matching dimension type |
| `TerrainDensity` | Density function: positive below `solidHeight`, negative above |
| `TerrainClimate` | Climate density functions derived from the terrain; carries the `TerrainLogic` to the biome source |
| `TerrainCaves` | Vanilla cave density, kept away from the surface |
| `MtmBiomeSource` | Biomes from `TerrainLogic.biomeAt` instead of vanilla's climate lookup |
| `MtmWater` | Fills rivers and lakes before the surface rules run |
| `TerrainCondition` | Surface rule condition for beaches, snow, bare rock and strata |
| `MtmCarving` | Keeps caves and ravines below the surface |
| `MtmOres`, `MtmSnow`, `MtmOasis` | Ores spread over the taller world, the MTM snow line, sugar cane at oases |
| `ForeignChunks` | Finds and fits region files merged in from other worlds when a world loads |
| `MtmConfig` | Reads and writes `config/mtm_worldgen.json` with comments |
| `SeedCapture` | Carries the seed into density functions while vanilla builds the noise router |

```mermaid
classDiagram
    direction LR
    class NoiseBasedChunkGenerator {
        <<vanilla>>
        fillFromNoise()
        buildSurface()
        applyCarvers()
    }
    class RandomState {
        <<vanilla>>
        create()
        router()
    }
    class TerrainDensity {
        compute(context) double
        mapAll(visitor)
    }
    class TerrainClimate {
        logic() TerrainLogic
    }
    class TerrainCaves
    class MtmBiomeSource {
        getNoiseBiome(quartX, quartY, quartZ, sampler)
    }
    class MtmWater {
        fill(logic, chunk)$
    }
    class TerrainCondition {
        begin(logic)$
        end()$
    }
    class MtmCarving {
        begin(logic)$
        end()$
    }
    class SeedCapture {
        begin(seed)$
        end()$
    }
    class TerrainLogic {
        <<core>>
    }
    class RandomStateMixin {
        <<mixin>>
    }
    class NoiseBasedChunkGeneratorMixin {
        <<mixin>>
    }
    class WorldPresetMixin {
        <<mixin>>
    }
    class MtmGenerators {
        create(access, vanillaSettings, settings)$
        terrainLogic(randomState)$
    }

    NoiseBasedChunkGenerator --> RandomState : noise router
    RandomState --> TerrainDensity
    RandomState --> TerrainClimate
    RandomState --> TerrainCaves
    NoiseBasedChunkGenerator --> MtmBiomeSource
    TerrainDensity --> TerrainLogic
    TerrainClimate --> TerrainLogic
    MtmBiomeSource --> TerrainLogic : via climate sampler
    RandomStateMixin ..> SeedCapture : around create()
    NoiseBasedChunkGeneratorMixin ..> MtmWater : before buildSurface
    NoiseBasedChunkGeneratorMixin ..> TerrainCondition : around buildSurface
    NoiseBasedChunkGeneratorMixin ..> MtmCarving : around applyCarvers
    NoiseBasedChunkGeneratorMixin ..> TerrainLogic : highestBlockInChunk
    WorldPresetMixin ..> MtmGenerators : creates the generator
    MtmGenerators ..> TerrainLogic
```

### Mixins

| Mixin | Hooks into | Why |
|---|---|---|
| `WorldPresetMixin` | `WorldPreset.createWorldDimensions` | Swaps the preset's placeholder for the real generator and build height from the config |
| `RandomStateMixin` | `RandomState.create` | Hands the seed to MTM density functions, which bind their `TerrainLogic` |
| `NoiseBasedChunkGeneratorMixin` | `fillFromNoise`, `buildSurface`, `applyCarvers` | Fills only up to the highest ground, places water, gives surface rules and carvers the logic, plants oases |
| `WorldCarverMixin` | `WorldCarver.carveBlock` | Every carved block passes here, so the surface can be protected |
| `HeightRangePlacementMixin` | `HeightRangePlacement.getPositions` | Stretches ore heights to the taller world, or leaves ores out |
| `ChunkGeneratorMixin` | `tryGenerateStructure` | Villages only on flat ground |
| `MinecraftServerMixin` | `setInitialSpawn` | Spawn chosen by the terrain logic, not vanilla's climate search |
| `ChunkSerializerMixin` | `ChunkSerializer.write` | Marks chunks with the world and algorithm version, to tell them from merged ones |
| `ServerLevelMixin` | `ServerLevel` init, `tickChunk` | Fits foreign chunks when the world loads; MTM snow line for weather |
| `SnowAndFreezeFeatureMixin` | `SnowAndFreezeFeature.place` | MTM snow line during generation |
| `BlenderMixin` | `Blender.of` | Turns off vanilla's blending toward old chunks |
| `RegistryDataLoaderMixin` | `loadRegistryContents` | Adds one MTM dimension type per possible build height |
| `WorldDimensionsMixin` | `isStableOverworld` | No "experimental" warning for the custom dimension type |

26.x versions have the same roles with different hooks, because vanilla rewrote its world generation in 26.1: filling, surface and carving happen together in `buildTerrain`, surface rules are material rules, and chunks are marked in `SerializableChunkData`. See CLAUDE.md for the details.

## Runtime flows

### Starting the game and creating a world

```mermaid
sequenceDiagram
    actor Player
    participant Loader as Loader entrypoint
    participant Preset as WorldPresetMixin
    participant Config as MtmConfig
    participant Gen as MtmGenerators
    participant RS as RandomStateMixin
    participant Logic as TerrainLogic
    participant Server as MinecraftServerMixin

    Loader->>Loader: register density function, biome source and<br/>surface condition codecs, /mwg commands
    Player->>Preset: Create World with "Realistic Landforms"
    Preset->>Config: load config/mtm_worldgen.json
    Config-->>Preset: WorldgenSettings
    Preset->>Gen: create(access, vanillaSettings, settings)
    Gen-->>Preset: generator + dimension type for the build height
    Note over Preset: settings are written into level.dat<br/>with the generator and stay locked
    Preset->>RS: vanilla creates RandomState(seed)
    RS->>RS: SeedCapture.begin(seed)
    RS->>Logic: TerrainLogic.forWorld(seed, settings)
    Note over RS,Logic: density functions bind the logic while<br/>vanilla maps the noise router
    RS->>RS: SeedCapture.end()
    Server->>Logic: findSpawn()
    Logic-->>Server: spawn column
```

### Loading a world with merged maps

```mermaid
sequenceDiagram
    participant Level as ServerLevelMixin
    participant FC as ForeignChunks
    participant Disk as Region files (.mca)
    participant Logic as TerrainLogic
    Level->>FC: prepare(level)
    FC->>Disk: scan files changed since last time
    FC->>FC: tell foreign chunks from MTM chunks by their tags
    FC->>Disk: back up, raise superflat maps, fill below old maps, write back
    FC->>FC: build the snapshot of edges (heights, water, biomes)
    FC->>Logic: setForeign(ForeignMap)
    Note over Logic: new terrain around the maps blends toward them,<br/>rivers end in lakes before them
```

### Generating a chunk

Vanilla drives chunk generation through its usual statuses. MTM answers each one from the terrain logic:

```mermaid
sequenceDiagram
    participant V as Vanilla chunk pipeline
    participant BS as MtmBiomeSource
    participant NG as NoiseBasedChunkGeneratorMixin
    participant D as TerrainDensity / TerrainCaves
    participant L as TerrainLogic
    participant W as MtmWater
    participant SC as TerrainCondition
    participant C as MtmCarving
    participant F as Features and structures

    V->>BS: biomes (4 × 4 × 4 cells)
    BS->>L: biomeAt(x, y, z)
    V->>NG: fillFromNoise
    NG->>L: highestBlockInChunk(chunk)
    Note over NG: fill only that many noise cells:<br/>the air above is never visited
    loop each noise cell corner, interpolated between
        NG->>D: compute(x, y, z)
        D->>L: solidHeight(x, y, z)
        L->>L: column cache or sampleSurfaceHeight
        D-->>NG: density (+ vanilla aquifers, ore veins, caves)
    end
    V->>NG: buildSurface
    NG->>W: fill(logic, chunk): river and lake water
    NG->>SC: begin(logic)
    Note over SC: surface rules ask for beach,<br/>snow, bare rock, strata
    NG->>SC: end()
    NG->>NG: MtmOasis.plant: sugar cane
    V->>NG: applyCarvers
    NG->>C: begin(logic)
    Note over C: caves and ravines kept below the surface
    NG->>C: end()
    V->>F: features (ores stretched by MtmOres,<br/>snow by MtmSnow), structures (villages on flat ground)
```

### Inside `sampleSurfaceHeight`

```mermaid
sequenceDiagram
    participant Q as Caller
    participant L as TerrainLogic
    participant LR as LogicRegion cache
    participant SK as SkeletonLayer
    participant ER as ErosionLayer
    participant RV as fitted RiverRegion
    participant LK as LakeRegion
    Q->>L: sampleSurfaceHeight(x, z)
    L->>LR: continent base (interpolated 64 block grid)
    L->>SK: skeleton height (tiers from plates, ranges, plateaus)
    L->>ER: erosion change and creeks
    L->>RV: nearest rivers within queryReach
    L->>L: carve: valley walls, floodplain, terraces, bars, fans, oxbows
    L->>LK: lake share and level
    L->>L: lake basin and rim, inland sink basin, dunes
    L->>L: creek valleys, sea stacks, sinkholes, foreign blending
    L-->>Q: height
```

### Threads

Chunk generation runs on vanilla's worker threads, often many at once (and more with mods like C2ME). The logic is safe for that:

- Every result is a pure function of the seed, the coordinates and the settings, so the order and the number of threads never change it.
- `RegionCache` computes each region exactly once, whichever thread asks first; others wait for it or read it without locks once it is there.
- Expensive steps run their rows on `LogicThreads`. A caller already on that pool runs them itself, and callers block instead of helping, so no thread waits on itself.
- Memos (`ColumnCache`, the last river and creek, the last plate belts) are per thread.

```mermaid
stateDiagram-v2
    [*] --> Missing
    Missing --> Computing: first get() on any thread
    Computing --> Cached: done
    Computing --> Computing: other threads wait
    Cached --> Held: hold() while another region needs it
    Held --> Cached: release()
    Cached --> Missing: evicted (least recently used, not held)
```

## Debugging and tools

| Tool | What it does |
|---|---|
| `/mwg locate mountain` | Nearest high mountain, with a coordinate to click |
| `/mwg debugmap` | PNG of terrain, rivers and region borders around the player (`HeightMapRenderer`) |
| `/mwg precompute` | Computes regions around the player ahead of time (`RegionPrecompute`) |
| `/mwg benchmark` | Chunk generation time against vanilla (`GenerationBenchmark`) |
| `java -jar <mod jar>` | Standalone map preview (`TerrainPreview`) |
| Amidst for MTM | Biome map, spawn and villages from a seed or save, by reflection into the mod jar |
| `gradlew :core:test` | Unit tests of the logic: determinism, order independence, region borders, rivers, lakes, slopes |
| GameTests | In-game tests on every loader and version, against one terrain hash |
