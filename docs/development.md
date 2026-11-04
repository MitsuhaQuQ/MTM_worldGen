# Building and development

Building needs JDK 17 and JDK 25 toolchains: 17 for Minecraft 1.20.1, 25 for Minecraft 26.x and Amidst. Gradle itself can run on JDK 21, and toolchains are not downloaded automatically. If Gradle cannot find a JDK, set `org.gradle.java.installations.paths` in `~/.gradle/gradle.properties`.

The terrain logic is plain Java in `core`. Each Minecraft version has its own modules in `versions/<version>`: `common` connects the logic to that version's generator, and one module per loader registers it. Gradle projects are named after them, as in `:26.3:fabric`. The versions, their loaders and the versions of those are in `gradle.properties`. A version close to another, such as a snapshot, can build on that version's modules with files of its own only where it differs (`base_version`); `versions/26.4` is set up that way for the 26.4 snapshot, but is not built yet.

```
gradlew build                          Build everything; jars are in versions/<version>/<loader>/build/libs
gradlew :core:test                     Unit tests of the terrain logic
gradlew :26.3:fabric:runClient         Run a development client
gradlew :1.20.1:fabric:runGametest     In-game tests, on Fabric
gradlew :1.20.1:forge:runGameTestServer
gradlew :26.3:forge:runGameTestServer
gradlew :26.3:neoforge:runGameTestServer
gradlew :amidst:test                   Tests of Amidst
gradlew :amidst:windowsZip             Amidst with Java and the Fabric jar for Windows (on Windows only)
```

| Where | Contents |
|---|---|
| `core`: `logic` | Terrain logic in plain Java, with no Minecraft classes |
| `core`: `noise` | Stateless, deterministic noise |
| `common`: `world` | Connection to the vanilla generator |
| `common`: `command` | Debug commands |
| `common`: `gametest` | In-game tests, run on every loader |

Every loader and Minecraft version is tested against the same terrain hash. After an intentional terrain change, update `EXPECTED_COLUMN_HASH` in each version's `MtmGameTests`; the failure message prints the new value.

See [How it works](how-it-works.md) for the design of the terrain logic, and [Architecture](architecture.md) for the code structure and runtime flows.
