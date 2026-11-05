# Architecture

How Realistic Landforms is laid out and how it runs, as of v0.7.1: the modules and packages, what each part is for, and how a world and its chunks are generated. The diagrams are UML in Mermaid, which GitHub renders in place. For the design ideas behind the terrain, see [How it works](how-it-works.md); for building and tests, see [Building and development](development.md).

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
| `SkeletonLayer` | 3 | Tier field (plains, hills, mountains) from the plates or, with `mountains.plateTectonics` off, from noise alone; mountain ranges, plateaus, hills, isolated peaks, cliff bands |
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
| `WaterSurface` | Water-column outlines shared by every version; reads a full two-column neighborhood so cleaned chunk borders agree, and preserves the levels used to carve the terrain |
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
        +lowered(ground, lake, oxbowBanks) RiverRegion
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
    TerrainLogic *-- "7" RegionCache : region caches
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
    VF["first fitting pass<br/>wide river valleys for tributaries"]
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
    RR --> VF
    ER --> VF
    LK --> VF
    VF --> FT
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

River fitting has an independent first pass on eroded ground. The final pass uses the valleys of wider rivers from that first pass when lowering small tributaries; it never reads its own final result. Oxbow levels are fitted lazily to the carved terrain along both banks of their crescent, before any oxbow excavation. Those bank queries use final rivers but never query oxbows, so the dependency remains acyclic. Every region holding the same oxbow samples the same global positions and obtains the same flat level.

### The tier field and plate tectonics

Which land is plains, hills or mountains, and where plateaus lie, comes from two fields that `SkeletonLayer` builds. With `mountains.plateTectonics` on (the default), the plates lay them out; off, they are noise alone, as before plates came in, and the belt, plate and plateau settings have no effect. Either way, thresholds are quantiles of the finished field, so the shares of each tier stay as configured.

```mermaid
flowchart TD
    Switch{"mountains.plateTectonics"}
    Noise["tier noise<br/>zoneWavelength"]
    Great["great plains field<br/>lowers the tier"]
    subgraph Plates["ContinentLayer.belt: every pair of nearby plates"]
        Conv["convergence along borders<br/>closing speed × closeness"]
        Relief["plate character<br/>lowland to upland"]
        High["highland plates<br/>plateChance"]
        Wide["wide convergence<br/>beltReach"]
    end
    Switch -- on --> Tier1["tier = zoneNoise × noise<br/>+ plateRelief × character<br/>+ beltStrength × convergence (rift below 0)<br/>− great plains"]
    Switch -- off --> Tier0["tier = noise − great plains"]
    Noise --> Tier1
    Noise --> Tier0
    Great --> Tier1
    Great --> Tier0
    Conv --> Tier1
    Relief --> Tier1
    Switch -- on --> P1["plateau field = zoneNoise × noise<br/>+ plateHighland × highland<br/>+ beltShare × wide convergence"]
    Switch -- off --> P0["plateau field = plateau noise"]
    High --> P1
    Wide --> P1
    Tier1 --> Q["quantile thresholds:<br/>plains, hills, mountains"]
    Tier0 --> Q
    P1 --> QP["quantile threshold:<br/>plateau coverage"]
    P0 --> QP
    Conv -. ranges follow borders,<br/>belts lean igneous .-> Ranges["mountain sites and links"]
```

Coasts still hold the tier down to the middle of the hills within `coastBand`, which turns mountains into hills along the sea.

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
    NG->>W: fill(logic, chunk)
    W->>L: sampleWaterLevel for the chunk and two columns around it
    Note over W: WaterSurface rounds the outline,<br/>water and bank blocks are placed
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

### Placing water

Water is placed column by column before the surface rules, at the levels the terrain was carved with. `WaterSurface` in `core` decides the outline, the same way on every version:

```mermaid
flowchart TD
    A["sampleWaterLevel for the chunk<br/>plus 2 columns on every side"] --> B["floor each level to a block,<br/>NaN is dry"]
    B --> C{"column and its<br/>4 neighbors"}
    C -- "wet, at most 1 wet neighbor" --> D["dry: a one-column spike"]
    C -- "dry, 3 or more wet neighbors" --> E["filled at the lowest neighbor:<br/>a one-column notch"]
    C -- otherwise --> F["kept at its own level"]
    D --> G["outline of the chunk plus 1 column"]
    E --> G
    F --> G
    G --> H["MtmWater: water from the bed up to the level,<br/>beds breaking the surface dug out (at most 4)"]
    G --> I["dry columns beside water: banks raised<br/>to the water, only where at most 4 blocks short"]
```

Levels are never smoothed afterwards. Falls come from the river logic, which already splits steep streams into level pools. Reading two columns around the chunk and returning one means both chunks along a border judge their shared columns from the same neighborhood, so the outline agrees across borders whatever order chunks generate in.

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

## Settings

```mermaid
sequenceDiagram
    participant File as config/mtm_worldgen.json
    participant Config as MtmConfig
    participant Settings as WorldgenSettings
    participant Level as level.dat
    participant Logic as TerrainLogic
    Config->>File: read (written with defaults and comments if missing)
    Config->>Settings: fromJson: missing fields take defaults, invalid values fail
    Note over Settings: resolved() scales the ranges by logicRange
    Settings->>Level: written with the generator when a world is created
    Level->>Logic: every later load reads the locked settings,<br/>never the config file
```

The config file only decides the settings of new worlds. A world keeps the settings it was created with, so editing the file never puts seams into it. Switches such as `general.caves`, `general.ores` and `mountains.plateTectonics` are locked the same way.

## Build, tests and release

```mermaid
flowchart LR
    subgraph Build["gradlew build"]
        Core["core sources"] --> C1["1.20.1 common"]
        Core --> C2["26.3 common"]
        C1 --> J1["fabric, forge jars<br/>(remapped, with refmap)"]
        C2 --> J2["fabric, forge, neoforge jars<br/>(not obfuscated)"]
        J1 --> Amidst["amidst jar and<br/>Windows app (jpackage)"]
    end
    subgraph CI["build.yml: every push"]
        UT["unit tests"]
        GT["GameTests: each version and loader"]
        PK["packaging"]
    end
    subgraph Release["release.yml: tag v&lt;mod_version&gt;"]
        CT["check-tag:<br/>tag matches mod_version"] --> AW["amidst-windows:<br/>Windows zip"]
        CT --> RL["release: jars, Amidst,<br/>notes from RELEASE_NOTES.md"]
        AW --> RL
    end
```

A release only publishes once every job has passed: when the Windows packaging failed after the move to Gradle 9, the tags for v0.6.0 and v0.7.0 published nothing, and v0.7.1 was the first release after v0.5.2.

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
