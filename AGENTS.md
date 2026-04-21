# AGENTS.md

## Module Scope
- `thermal` implements environmental thermodynamics simulation and thermal data/config plumbing.
- Depends on `:base`; do not duplicate utilities already provided by `base`.

## Build and Dependency Facts
- Standalone Gradle module and git submodule; also included by root multi-project build.
- Uses Java toolchain 21 and Kotlin JVM target 21.
- Uses `net.neoforged.moddev` and Kotlin for Forge.
- Declares `implementation(project(":base"))`.

## Main Entrypoint and Core Areas
- Entrypoint: `src/main/kotlin/dev/deepslate/fallacy/thermal/TheMod.kt`.
- Engine contracts and runtime:
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/ThermodynamicsEngine.kt`
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/impl/EnvironmentThermodynamicsEngine.kt`
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/ChunkScanner.kt`
- Attachments and state:
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/ModAttachments.kt`
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/data/*`
- Datapack registry and generation:
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/datapack/ModDatapacks.kt`
  - `src/generated/resources/data/fallacy_thermal/fallacy_thermal/biome/configuration.json`
- Commands:
  - `src/main/kotlin/dev/deepslate/fallacy/thermal/command/RegisterHandler.kt`

## Java Injection and Mixin Points
- Thermal block/level extension interfaces:
  - `src/main/java/dev/deepslate/fallacy/thermal/inject/BlockWithThermal.java`
  - `src/main/java/dev/deepslate/fallacy/thermal/inject/ThermalExtension.java`
- Mixins:
  - `src/main/java/dev/deepslate/fallacy/thermal/mixin/thermal4block/BlockMixin.java`
  - `src/main/java/dev/deepslate/fallacy/thermal/mixin/thermal4level/LevelChunkMixin.java`
  - `src/main/java/dev/deepslate/fallacy/thermal/mixin/thermal4level/ServerLevelMixin.java`
- Interface injection config:
  - `src/main/resources/META-INF/interfaceinjection.cfg`

## Registration and Event Flow
- Engine update/scanning is driven by server/world/player tick events in `ThermodynamicsEngine.Handler`.
- Chunk load/unload, shutdown, and datapack sync lifecycle are handled in `EnvironmentThermodynamicsEngine.Handler`.
- Thermal attachments are registered in `ModAttachments.RegisterHandler` on construct event.
- Command registration is centralized in `command/RegisterHandler.kt`.

## Concurrency and Safety Constraints
- Core engine uses async worker tasks (`Worker.IO_POOL`) plus region reservation/striped locks.
- Any change in `EnvironmentThermodynamicsEngine` must preserve:
  - version checks before committing async results,
  - proper requeue behavior on stale snapshots,
  - release of reserved regions and decrement of active task counters on all code paths.
- Prefer small, testable changes in engine scheduling logic; avoid broad refactors without compile/build validation.

## Resources and Datagen
- Mod metadata template: `src/main/templates/META-INF/neoforge.mods.toml`.
- Mixin config: `src/main/resources/fallacy_thermal.mixins.json`.
- Datagen output under `src/generated/resources` is part of shipped resources.

## Verification
- Fast check: `./gradlew :thermal:compileKotlin`
- Full module build: `./gradlew :thermal:build`
