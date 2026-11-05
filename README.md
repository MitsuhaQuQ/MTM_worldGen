# Realistic Landforms

Real-scale, believable terrain for Minecraft: continents, plains, hills and towering mountains, with rivers that start in the uplands, grow on their way down and reach the sea. One block is one metre, so there is room for long railways, big cities and whole river basins. Mountains are scaled up enough to be worth looking at.

It is also **faster than vanilla**, in a world more than twice as tall: about half the time per chunk on 1.20.1, and still a little faster on 26.3, where vanilla itself became three times faster.

Formerly MTM Worldgen; the mod id is still `mtm_worldgen`. It adds a new world type. Existing worlds, the Nether and the End are not affected.

> **Early development.** Terrain changes between versions, and worlds made with one version may not load in the next. Do not use it for worlds you want to keep yet.

## Highlights

- **Rivers that behave like rivers.** Every river runs downhill from its source to a sea or lake, gathers tributaries and widens as it goes, from mountain streams to trunks hundreds of blocks wide. They cut valleys, spread floodplains, fall over waterfalls, and leave alluvial fans, terraces, oxbow lakes, sand and gravel bars, and deltas.
- **Lakes that fill real basins.** Lake levels are set by where the basin spills, so lakes sit high in the mountains as well as by the sea, with outlets that feed rivers.
- **Mountains carved by water.** An erosion simulation cuts mountain ranges and hills into branching gullies, ravines and spurs, with creeks running down them. Ranges blend between steep, jagged igneous peaks and broad, rounded worn-down ones.
- **Landscapes that fit together.** Plains reach mountains through hills, snow lines follow the climate, and vegetation changes with altitude from forest to alpine meadow and snow. Biomes form large patches instead of a mosaic.
- **Tall worlds.** The surface reaches Y=750 by default, up to 2032 if you configure it, and ores are spread over the taller range.
- **Fast and multithreaded.** See [Performance](#performance).
- **The same world everywhere.** A seed gives exactly the same terrain on Fabric, Forge and NeoForge, on every supported Minecraft version, and in whatever order you explore it.

## Terrain

- **Continents and oceans.** Land is grouped into continents separated by straits and open oceans, about 30% ocean by default.
- **Plate tectonics.** Continents are made of drifting plates. Mountain belts rise where plates collide, with ranges running along the collision, and plains spread where they pull apart. Whole plates are lowland, upland or highland, so plains, plateaus and mountain country each cover areas tens of kilometres across.
- **Plains, hills and mountains.** Hills always lie between plains and mountains and can also stand alone. Great plains over ten thousand blocks across leave room for big cities.
- **Mountains.** Ranges follow winding ridges, with isolated peaks where hills meet mountains and rounded dome hills among the hills. Along coasts, mountains give way to hills.
- **Plateaus.** Table lands at a common height, with steep or gentle edges, on highland plates and beside mountain belts.
- **Rock detail.** Cliff bands with exposed rock strata on steep faces, overhangs on high cliffs, sea stacks off rocky coasts, and sinkholes in dry uplands that open into caves.
- **Deserts.** Fields of dune ridges shaped by a single wind direction, with small oases of grass, trees and sugar cane between them.
- **Climate.** Tropical, temperate, cold and polar zones, each with its own snow line and biomes. All vanilla biomes are kept, and the deep dark spreads deep underground.
- **Coasts and islands.** Sand, gravel, rock and sea cliff coasts, and sand, rock and mushroom islands in the open sea.
- **Caves.** Vanilla caves and ravines are kept but stay below the surface, so hillsides and riverbeds are not torn open.
- **Villages.** Only on flat ground.

Every detail feature can be switched off in the configuration.

Not done yet: harbors, rias and tidal flats, sandbars, glacial landforms, rock debris below cliffs, and rain shadows.

## Performance

Most terrain mods build on vanilla's generator as it is, with data packs that retune or extend its noise. Vanilla's terrain is a 3D density, so it cannot know where the ground is without evaluating noise through the whole column, from the bottom of the world to the build limit, and running aquifer and ore vein rules on every block, air included. The work per chunk grows in proportion to the build height: a world raised to Minecraft's maximum of 2032 does more than five times the work of vanilla in every chunk, even where the ground is at sea level. Richer terrain makes every one of those points more expensive too.

Realistic Landforms computes terrain as a height map instead. It knows the highest ground in a chunk before filling it, and stops just above it, so the air is never visited. The cost of a chunk follows the height of its ground, not the build limit: a world twice as tall hardly costs more. [How it works](docs/how-it-works.md#why-a-taller-world-costs-vanilla-more) explains this in detail.

| Minecraft | Vanilla, -64 to 320 | Realistic Landforms, -64 to 800 |
|---|---|---|
| 1.20.1 | about 12 ms per chunk | about 5.6 ms (47%) |
| 26.3 | about 3.9 ms per chunk | about 3.6 ms (93%) |

Measured with `/mwg benchmark` on the same machine, single threaded, over the same chunk positions, for the biome and noise stages, caves included. Run it yourself to compare on your machine. Other terrain mods have not been benchmarked side by side.

- **Large-scale logic is computed once per area.** Rivers, lakes and erosion are worked out for 4096 by 4096 block areas in the background, taking about 0.5 to 1.4 seconds when you first enter one. New chunks there appear a little later, but the server does not freeze. `/mwg precompute` works out the areas around you ahead of time.
- **Results are kept on disk.** Erosion results are saved in the world folder, so areas are not computed again after a restart.
- **Bounded memory.** At most 64 areas are kept in memory, a few tens of megabytes.
- **Safe for parallel generation.** Terrain only depends on the seed and coordinates, so any number of threads gives the same result. Pre-generation with C2ME and Chunky has been tested, including adding C2ME to a world part way through.

## Requirements

| Minecraft | Java | Fabric | Forge | NeoForge |
|---|---|---|---|---|
| 1.20.1 | 17 | Loader 0.16.10, Fabric API 0.92.2 | 47.3.0 | |
| 26.3 | 25 | Loader 0.20.1, Fabric API 0.165.2 | 66.1.5 | 26.3.0.101 |

No other mods are required. Each jar is built for one Minecraft version, as its name says: `mtm_worldgen-fabric-<version>-26.3.jar`. The 26.4 snapshots are not supported yet.

26.3 also places the vanilla biomes 1.20.1 does not have: the pale garden, the dappled forest and sulfur caves. Worlds cannot be moved from 1.20.1 to 26.x yet.

## Getting started

1. Install the jar for your loader and Minecraft version.
2. In the Create World screen, open the **World** tab and choose **Realistic Landforms** as the world type.

On a server, set `level-type=mtm_worldgen:realistic` in `server.properties`.

## Merging other maps

You can copy region files from other worlds, vanilla, superflat or older versions, into a Realistic Landforms world. They are detected when the world loads and fitted in: superflat maps are raised to normal ground height, missing ground below old maps is generated, and terrain generated around them afterwards blends toward their edges, with rivers ending in lakes before them. The original region files are backed up under `<world>/mtm_worldgen/backup/` before they are changed. Clear the chunks around a merged map to have them generate again with the blend.

## Configuration

Settings live in `config/mtm_worldgen.json`, created on first use with a comment explaining each setting. They cover heights and slopes, how much of the world is ocean, plains, hills or mountains, the size of continents, mountains and climate zones, rivers, erosion, deserts, coasts, snow lines, biomes, and each detail feature.

The file only applies to new worlds. Each world keeps the settings it was created with, so later edits cannot cause seams in it.

## Commands

All commands need cheats enabled.

| Command | Effect |
|---|---|
| `/mwg locate <kind>` | Finds the nearest `mountain`, `plains`, `plateau`, `lake` or `ocean`, with a coordinate you can click to teleport |
| `/mwg flyspeed <factor>` | Sets flying speed, 0.1 to 50 times the default. `reset` restores it, and no argument shows it |
| `/mwg precompute <radius>` | Computes the areas within this many areas of you (0 to 5) in the background, so exploring there later does not wait. `cancel` stops it |
| `/mwg benchmark` | Compares chunk generation time with vanilla |
| `/mwg debugmap [blocksPerPixel]` | Saves a map of the terrain and rivers around you to `<world>/mtm_worldgen/debug/` |

## Map viewer

Each release also carries Amidst for MTM, which draws the biome map, world spawn and villages of a Realistic Landforms world from its seed or a save, without starting the game:

- `AMIDST-MTM-<version>-windows.zip`: unpack it and run `AMIDST-MTM.exe`. It comes with its own Java and the mod.
- `amidst-mtm-<version>.jar`: on any system with Java 25, run `java -jar amidst-mtm-<version>.jar -mtmjar <mod jar> [-mtmgamedir <game directory>]`.

Saves use the settings stored with them; worlds from a seed use `config/mtm_worldgen.json` of the game directory, or the defaults. Structures other than villages are approximate. Amidst for MTM is based on [Amidst](https://github.com/toolbox4minecraft/amidst), with newer Minecraft support from [MitsuhaQuQ/amidst](https://github.com/MitsuhaQuQ/amidst).

## More

- [How it works](docs/how-it-works.md): why vanilla's terrain rules are not enough, and how terrain, rivers and lakes are computed
- [Architecture](docs/architecture.md): code structure and how a world and its chunks are generated, with UML diagrams
- [Building and development](docs/development.md)

## License

[MIT](LICENSE), except Amidst in `amidst/`, which is [GPLv3](amidst/LICENSE.txt).
