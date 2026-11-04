# Release notes

## v0.6.0

Builds for Minecraft 26.3, on Fabric, Forge and NeoForge. 1.20.1 stays the main version. Terrain is the same block for block in every version and on every loader; worlds cannot be moved from 1.20.1 to 26.x yet.

### Minecraft versions

- Minecraft 26.3 (Java 25): Fabric Loader 0.20.1 with Fabric API 0.165.2, Forge 66.1.5 and NeoForge 26.3.0.101. Each jar names the Minecraft version it is for, as in `mtm_worldgen-neoforge-<version>-26.3.jar`
- No build for the 26.4 snapshots yet: they build chunks in a new way that MTM's water, surface and cave protection still have to be fitted to, and only Fabric supports them so far
- In 26.3 the pale garden grows on temperate highlands next to cherry groves, the dappled forest among the temperate forests, and sulfur caves join the cave biomes. These lists only change the defaults of new configs in 26.3

### Development

- The terrain logic moved to a plain Java module, `core`, which every version builds into its jar. Each Minecraft version has its own modules under `versions/`
- The build runs on Gradle 9.6 with Architectury Loom 1.17
- The unit tests share one terrain logic where they only read the default world, which cuts them from about 7 minutes to 2
- The terrain hash of the game tests is the same in every version. It counts vanilla's ore veins as stone: 26.x places them after the block columns the hash reads

## v0.5.1

Fixes for raised land along steep streams. Chunks already saved by earlier versions count as an older version, and terrain generated around them blends toward their edges.

### Water

- The pools of a steep stream no longer stand tens of blocks above the slope, holding the land up around them in round snowy domes hundreds of blocks across with the stream in a deep slot through them. Pools now stay level at the ground half way along them
- The banks of a river only hold up the land as far as its own water needs, falling away steeply from the channel. A steep stream beside a lower river raised the land where they meet into a round mound
- Round plateaus beside mountain streams, a known issue, are mostly gone with it

## v0.5.0

Lakes take their shape from the ground, and rivers lay sediment bars. Chunks already saved by earlier versions count as an older version, and terrain generated around them blends toward their edges.

### Water

- The shore of a lake follows the terrain around it. Lakes used to fill the rounded outline of the routing cells they lie in and stand above the ground at its edge, held in by a raised rim: a square lake walled in on flat land. Each lake now floods the ground around it on a finer grid, which gives the level it really spills at and where its water reaches (`rivers.lakeShoreStep`)
- Rivers run on through the routing cells of a lake, which can now be smaller than its cells, instead of ending at its outline

### Settings

- New `general.logicRange`, from 1 to 3: how far the terrain logic looks around each place. Higher values route rivers, erode mountains and shape valleys over a wider area, for bigger rivers, wider and flatter river plains and smoother joins between logic regions, with the same landform sizes and the same share of plains, hills and mountains. New terrain takes longer to appear: at 2, about 10 seconds per logic region instead of 1.5
- The config file now explains every setting in comments. An existing file without comments is written again with them when the game starts, keeping its values
- `general.maxSurfaceHeight` can be set as high as Minecraft's build limit of 2032, as in JJThunder's worlds; creating a world with it used to crash. Near the limit the world simply ends at 2032, with no headroom above the highest peaks

### River landforms

- Rivers carry sediment: a short budget down each river adds what the land draining into it sheds, more below steep ground, and drops what the river cannot carry on. Where it drops its load, bars build along the banks, as gravel below steep reaches and as sand beaches on slow lower reaches, widest on the inside of bends. Steep rivers carry everything on and keep bare banks, and lakes trap all of it (`rivers.bars` and the `silt*`, `bar*` settings)

## v0.4.2

Fixes for lake outlets and creeks. Chunks already saved by earlier versions count as an older version, and terrain generated around them blends toward their edges.

### Water

- A lake that tributaries or trunks flow into keeps its outlet even when it is short and steep, with waterfalls taking the drop. Leaving the outlet out made such lakes look like inland lakes with no way out, in forests far from any desert
- Creeks that run their whole course as one straight line, with no other creek joining them, are left out (`erosion.creekStraightCells`, `erosion.creekStraightShare`). On even slopes erosion carved rows of parallel gullies, and their creeks ran side by side as straight ditches

## v0.4.1

A fix for the Fabric jar, which crashed the game at startup outside the development environment (`could not find any targets matching 'carveBlock' ... No refMap loaded`). Its mixin config now names the refmap that maps vanilla's method names, and the build checks both loaders' jars for it. Terrain is unchanged from v0.4.0, and the Forge jar was never affected.

## v0.4.0

Dunes and oases in the desert. Terrain in and around deserts has changed since v0.3.2: chunks already saved by v0.3.2 count as an older version, and terrain generated around them blends toward their edges. New settings take their defaults when an existing `config/mtm_worldgen.json` lacks them; delete the file to have them all written out.

### Terrain

- Hills in the desert flatten into low, gentle ground, which keeps its height instead of sinking into basins that would fill with lakes (`deserts.hillRelief`)
- Fields of dune ridges cover much of the desert: they run across one wind direction per world, with a gentle windward side and a steeper lee, and break into separate dunes along their crests. They keep back from rivers and lakes and fade out before the edge of the desert (`deserts.dunes`)
- Oases: here and there a small pond lies in a flat hollow between the dunes, ringed by grass and a few trees, with sugar cane along the water (`deserts.oases`, `biomes.oasis`)
- Creeks no longer run through deserts

### Settings

- New `deserts` group for dunes and oases; both can be switched off

## v0.3.2

A fix for rivers below waterfalls. Chunks already saved by v0.3.1 count as an older version, and terrain generated around them blends toward their edges.

### Water

- The pool above a waterfall no longer raises a round plateau about a hundred blocks across around the fall, which walled off streams running into the river below it

## v0.3.1

A fix for creeks in mountains with cliff bands. Chunks already saved by v0.3.0 count as an older version, and terrain generated around them blends toward their edges.

### Water

- Creeks no longer run uphill across cliff ledges before dropping off the next cliff: cliff bands give way along creeks, which cut a notch through them, and follow the land as erosion left it

## v0.3.0

New river landforms and Amidst for MTM worlds. Terrain along rivers and creeks has changed since v0.2.0: chunks already saved by v0.2.0 count as an older version, and terrain generated around them blends toward their edges. New settings take their defaults when an existing `config/mtm_worldgen.json` lacks them; delete the file to have them all written out.

### Water

- Creeks cut small V-shaped valleys into the slopes instead of lying on them (`erosion.creekIncision`, `erosion.creekValleyGrade`)
- Alluvial fans: where a stream leaves steep ground for gentle ground it spreads a low fan of sediment, with the stream running in a trench across it (`rivers.fans`)
- River terraces: the sides of hill and mountain valleys step down to flat benches along the larger rivers (`rivers.terraces`)
- Oxbow lakes: crescent lakes left by cut-off meanders lie beside rivers winding over plains (`rivers.oxbows`)
- Each of these can be switched off in the configuration

### Tools and settings

- Releases carry Amidst for MTM worlds: `AMIDST-MTM-<version>-windows.zip` runs on Windows without Java or Minecraft, and `amidst-mtm-<version>.jar` runs anywhere with Java 25. It draws the biome map, world spawn and villages of a seed or a save

## v0.2.0

Terrain has changed throughout since v0.1.0, so create a new world to see it. Chunks already saved by v0.1.0 count as an older version: terrain generated around them blends toward their edges. If you keep a `config/mtm_worldgen.json` from v0.1.0, delete it to get the new settings written with their defaults.

### Terrain

- Mountains and hills are carved by a water erosion simulation, with branching gullies, ravines and spurs (`erosion` settings; turn it off to get the previous ridge texture back)
- Rivers are the base level of erosion, so mountain valleys run down to the rivers that drain them
- Plateaus are their own zones with table tops at a common height, and wide great plains join up for large cities
- Horizontal scale settings (`scale`) make continents, mountain ranges, plateaus, hills, climate zones and biome patches larger or smaller
- Badlands no longer form on mountainsides, where their banded terracotta wrapped peaks in rings (`biomes.unrealisticOnMountains`)
- The deep dark spreads in patches deep underground (`biomes.deepDark`)

### Water

- Steep rivers fall through level pools and short waterfalls instead of long one-block steps (`rivers.waterfalls`)
- Creeks run down the eroded gullies and into the rivers (`erosion.creeks`)
- River sources no longer sit raised above the slope around them
- Rivers through mountain lakes keep the lake's level: lakes no longer drain into stepped bowls, and rivers are no longer blocked by mounds at the shore
- Rivers leaving mountain lakes are about half as wide as before

### Tools and settings

- Terrain preview: run `java -jar` on the mod jar to browse the terrain of a seed in a window, or `java -jar <mod jar> <seed> [x z blocksPerPixel picture.png]` to write a map picture
- Debug commands now start with `/mwg`; `/mwg precompute <radius>` works out the regions around you ahead of time
- Eroded regions are cached under `mtm_worldgen/erosion` in the world folder, so they are not computed again after a restart
- Config switches `general.caves`, `general.ores` and `general.undergroundFallingBlocks`

### Performance

- New worlds no longer hang at 0% while the spawn and strongholds are placed
- Regions next to the one you are in are computed in the background, so new terrain appears with less delay
- The zoomed-out preview, coarse debug maps and `/mwg locate mountain` no longer compute erosion over wide areas
- The first visit to a new 4096-block region still takes 0.5–1.5 s of computing before its chunks appear

## v0.1.0

First public build of MTM-Worldgen for Minecraft 1.20.1, on Fabric and Forge. It is an early preview: terrain may still change between versions, and worlds made with it should not be relied on for long-term saves yet.

Choose the world type **MTM Realistic** when creating a world. Settings live in `config/mtm_worldgen.json` and are locked into each world when it is created.

### Terrain

- Realistic landforms at a 1 block = 1 meter scale: plains, hills and mountain ranges with ridges, spurs and gullies, igneous and wind-worn mountain styles, plateaus and isolated peaks; surface height up to 750 by default (build height -64 to 800)
- Cliff bands with exposed rock strata on steep faces, overhangs on high cliffs, sea stacks off rocky coasts
- Sinkholes in dry uplands that open into caves; vanilla caves kept away from the surface
- Ores spread over the taller rock at vanilla density

### Water

- Drainage routed across the world: streams, tributaries and trunk rivers that widen on the plains, with valleys, floodplains and lakes with outlets
- Estuaries that open toward the sea, river deltas with sand and clay flats, inland lakes in deserts

### Climate and biomes

- Climate zones from tropical to polar with snow lines; biomes follow altitude bands from lowland forest to alpine meadow and snow peaks
- Beaches, gravel shores, rocky shores and sea cliffs; sand, rock and mushroom islands

### Merging other maps

- Region files merged in from other worlds (vanilla, superflat or older versions) are detected at startup and fitted in: superflat maps are raised, missing ground below old maps is generated, and new terrain around them blends toward their edges, with rivers and lakes held back from lower ground
- Original region files are backed up under `mtm_worldgen/backup` before they are changed

### Known limitations

- Merged chunks only blend with terrain generated after the merge; clear the chunks around a merged map to have them regenerate
- NeoForge and other Minecraft versions are not supported yet
