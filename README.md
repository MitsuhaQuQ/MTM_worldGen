# MTM Worldgen

A Minecraft mod that replaces terrain generation with realistic, continuous landforms, river networks and climate zones.

- Minecraft 1.18.2 (primary), later versions to follow
- Forge and Fabric, built from a shared `common` module with Architectury Loom
- License: MIT

## Status

Early development; worlds from one version may not load in the next. Working on Minecraft 1.18.2 with Fabric and Forge:

- Continents, climate zones and biomes assigned from terrain
- Mountain ranges, plateaus and hills, with a surface height of 750 by default
- Drainage: rivers from streams to major trunks, lakes, carved valleys
- Generation is deterministic and identical across loaders, and fills chunks in about 27% of the vanilla time
  (biome and noise stages only)

Not done yet: plains shaped by rivers, deltas, coast types and islands, sinkholes and karst, vegetation zones, saving
terrain logic to disk, and blending into existing worlds.

## Building

```
gradlew build
```

Output jars are in `fabric/build/libs` and `forge/build/libs`.

## Running in development

```
gradlew :fabric:runClient
gradlew :forge:runClient
```
