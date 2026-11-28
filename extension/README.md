# Gboard extension dex (`gboard-patches.rve`)

This nested Gradle build produces the extension dex that every patch in this repository calls into.
Its output is committed as `../src/main/extensions/gboard-patches.rve`, because the patches cannot
run without it and it cannot be rebuilt from the Kotlin sources alone.

```bash
../tools/build-extension.sh   # builds, then copies the dex into src/main/extensions/
```

## Layout

| Path | What it is |
| --- | --- |
| `extensions/extension/src/main/java` | The runtime: one package per feature (`atmention`, `backuprestore`, `keyboard`, …) |
| `extensions/extension/src/main/settings-text` | English authority plus per-locale translations for the settings rows; the build generates both Android string resources and the in-code catalog |
| `extensions/extension/native-payload` | `libgboardpatches_quickjs.so`, embedded into a generated Java class |
| `extensions/extension/image-payload` | The Google-colour image used by the spacebar logo feature |
| `extensions/extension/build.gradle.kts` | The extension definition, its generators, and the `syncExtension` checks |
| `patches/` | Empty placeholder: the Morphe Gradle plugin always includes `:patches`, but this build only compiles the extension |

The custom-theme bindings are generated from `../src/main/resources/gboard/gboard-version-bindings.json`,
the same reviewed profile the Kotlin patches use, so the extension and the patches always agree on
class names for a release.

## Prerequisites

- **JDK 21+** (`JAVA_HOME`, or `~/.local/jdk-21`).
- **Android SDK** — `ANDROID_HOME`, or `sdk.dir` in `local.properties` (git-ignored). `compileSdk`
  and build-tools come from the Gradle Android plugin defaults.
- **Morphe Gradle plugin 2.1.2** — the `app.morphe.patches` plugin is not on the public plugin
  portal, so publish `MorpheApp/registry`'s `morphe-patches-gradle-plugin` 2.1.2 to `mavenLocal`
  first (`./gradlew publishToMavenLocal` in that project). `settings.gradle.kts` looks in
  `mavenLocal()` before the configured repositories for exactly this reason.

The build is otherwise offline-friendly: dependencies resolve from Maven Central and Google, and
the wrapper pins its Gradle distribution in `gradle/wrapper/gradle-wrapper.properties`.

## What it produces

`extensions/extension/build/morphe/extensions/gboard-patches.rve` — a dex of the runtime, which
`syncExtension` validates before writing (LAN FTP types present, no `RecordTag` synthetic). Copy it
over `../src/main/extensions/gboard-patches.rve`; `tools/build-extension.sh` does that for you.

Rebuild it whenever the runtime changes. A patch that calls a runtime method missing from this dex
fails at apply time, not at build time, so the dex in the repository must match the Kotlin sources.
