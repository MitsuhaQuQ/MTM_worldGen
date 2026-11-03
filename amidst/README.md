
---

>[!IMPORTANT]
>:warning: This project is searching for a new maintainer. If you are interested, please get in touch. See [#1144](https://github.com/toolbox4minecraft/amidst/issues/1144).

---

Amidst
======

[![Build Status](https://travis-ci.org/toolbox4minecraft/amidst.svg?branch=master)](https://travis-ci.org/toolbox4minecraft/amidst)

## What is Amidst?

Amidst is a tool to display an overview of a Minecraft world, without actually creating it.

Amidst **can**:

* render an overview of a world from a given seed and Minecraft version
* save an image of the map
* use a save game
* display biome information
* display slime chunks
* display end islands
* display the following structures
  * world spawn
  * strongholds
  * villages
  * witch huts
  * pillager outposts
  * jungle temples
  * desert temples
  * igloos
  * abandoned mine shafts
  * ocean monuments
  * ocean ruins
  * shipwrecks
  * buried treasures
  * nether fortresses
  * end cities
  * since Minecraft 26.1 also bastion remnants, nether fossils, ruined portals, ancient cities, trail ruins, trial chambers and abandoned camps

### Minecraft 26.1 and later

Since 26.1, Minecraft ships with readable class names. For these versions, Amidst loads the built-in data pack the way a server does and asks Minecraft itself for biomes, the world spawn and where structures go, so the map follows the multi-noise biomes and structure sets of the selected version. This needs Java 25, like the game. The map shows the surface biomes; cave biomes such as the deep dark are not drawn. Structure icons are at the coordinates that `/locate` reports. Saves of 26.1 and later can be opened, but their players are not shown yet.

Minecraft 1.18 to 1.21.11 are obfuscated and use the new world generation, so Amidst does not support them.

Amidst **cannot**:

* display changes that were applied to a save game like
  * changes made by world editors like MCEdit
  * changes made while loading the world in Minecraft
* find individual blocks or mobs like
  * diamond ore
  * cows

## MTM Worldgen

This fork also shows worlds of the MTM Worldgen mod. It runs the terrain logic straight from the mod jar, so it needs no Minecraft installation:

```
java -jar amidst.jar -mtmgamedir <game directory> [-mtmjar <mtm_worldgen jar>]
```

On Windows, the release zip `AMIDST-MTM-<version>-mtm<mod version>-windows.zip` needs no Java or Minecraft: unpack it and run `AMIDST-MTM.exe`. It ships its own Java runtime and a jar of MTM Worldgen, which it uses unless `-mtmjar`, `-profile` or `-mcjar` is given. `scripts/package-mtm-windows.ps1 -MtmJar <mtm_worldgen jar>` builds it after `mvn package`.

* The game directory provides `saves/`, `config/mtm_worldgen.json` and, unless `-mtmjar` is given, the jar in `mods/`.
* Opened saves use the settings locked in their `level.dat`. Worlds from a seed use the config file, or the defaults of the mod.
* A launcher profile with MTM Worldgen in its mods folder also switches to MTM worlds.
* The map, the world spawn and the flat-ground rule for villages come from the mod. Other structures follow the 1.17 rules Amidst knows, so they are approximate. Foreign chunks merged into a world are not shown, and the Nether is not available.

## Amidst has found a new home

Amidst was moved to a new location, since Skidoodle aka skiphs is too busy to maintain it. It has also found some new developers. One of them is DrFrankenstone, a.k.a. Treer, who is the developer of AmidstExporter. Skidoodle is still an owner of Amidst and agreed to move the project.

### Links

* [Download](https://github.com/toolbox4minecraft/amidst/releases)
* [FAQ](https://github.com/toolbox4minecraft/amidst/wiki/FAQ)
* [Wiki](https://github.com/toolbox4minecraft/amidst/wiki)
* [Reporting a Bug](https://github.com/toolbox4minecraft/amidst/wiki/Supporting-the-Development#reporting-a-bug) - please report bugs, so we can fix them
* [Requesting a Feature](https://github.com/toolbox4minecraft/amidst/wiki/Supporting-the-Development#requesting-a-feature)
* [Thread in the minecraftforum](https://www.minecraftforum.net/forums/mapping-and-modding-java-edition/minecraft-tools/2970854-amidst-map-explorer-for-minecraft-1-14)
* [Project Page](https://github.com/toolbox4minecraft/amidst)
* [Supporting the Development](https://github.com/toolbox4minecraft/amidst/wiki/Supporting-the-Development)
* [License Text](https://github.com/toolbox4minecraft/amidst/blob/master/LICENSE.txt)

## What is my internet connection used for?

* Amidst **will** use web services provided by Mojang, e.g. to
  * display information about Minecraft versions.
  * display information about players like the name or the skin.
* Amidst **will** check for updates on every start.
* Amidst **will not** track you with Google Analytics, which was the case in older versions.

## Legal Information

* Amidst is **not** owned by or related to Mojang in any way.
* Amidst comes with **absolutely no warranty**.
* Amidst is free and open source software, licensed under the GPLv3.

## Screenshots

These screenshots are created from the seed 24922 using Amidst v4.0 and Minecraft 1.9.

![default](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_default.png)

### The End Dimension

![The End Dimension](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_end.png)

### Biome Highlighter

![Biome Highlighter](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_biome-highlighter.png)

### Grid

![Grid](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_grid.png)

### Slime Chunks

![Slime Chunks](https://raw.githubusercontent.com/wiki/toolbox4minecraft/amidst/screenshots/screenshot_default_24922_slime.png)
