# Insulin

<p align="center">
  <img src="https://raw.githubusercontent.com/FlyInMyEye/Insulin/master/assets/logo.png" alt="Insulin" width="300">
</p>

<p align="center">
  <a href="https://github.com/FlyInMyEye/Insulin"><img src="https://img.shields.io/github/stars/FlyInMyEye/Insulin?style=flat&label=GitHub" alt="GitHub"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--only-blue.svg?style=flat" alt="License"></a>
</p>

<p align="center"><i>Son, are you meshing your chunks again?</i></p>

Insulin is a client-side Minecraft optimization mod that caches terrain meshes. When you return to previously visited terrain, cached doppelgangers appear while the real chunks load and replace them seamlessly.

---

## Features

- Persistent terrain mesh cache for every world and dimension
- Fast doppelgangers during world joins, teleports, and chunk loading
- Camera-first loading with adaptive per-frame performance limits
- Solid, cutout, translucent, empty, and block-entity section support
- Automatic invalidation when terrain, resources, or renderer settings change
- Iris shader switching without remeshing everything
- Optional performance and cache statistics overlay

---

## Installation

1. Install Fabric Loader with Fabric API, or Forge, for Minecraft 1.20.1
2. Install Sodium 0.5.13 on Fabric, or Embeddium 0.3.31 on Forge
3. Place the Insulin jar in your `mods` folder
4. Launch the game

Insulin is client-side and does not need to be installed on a multiplayer server.

### Supported Versions

| Minecraft | Loader | Renderer | Status |
|-----------|--------|----------|--------|
| 1.20.1 | Fabric | Sodium 0.5.13 | Active |
| 1.20.1 | Forge 47.x | Embeddium 0.3.31 | Experimental |

---

## Commands

- `/insulin stats on` enables the statistics overlay
- `/insulin stats off` disables the statistics overlay
- `/insulin storage stats` shows RAM and disk cache usage
- `/insulin storage wipe` clears the cache and rebuilds loaded terrain

---

## Building from Source

```sh
./gradlew buildAll
```

Built jars are in `versions/v1_20_1/<loader>/build/libs/`.

---
