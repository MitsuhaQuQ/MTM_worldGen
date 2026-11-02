# Release notes

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
