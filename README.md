<div align="center">

<img src="https://raw.githubusercontent.com/RATR2/repack/main/src/main/resources/pack.png" alt="Repack logo" width="128" height="128">

# Repack

[![Issues](https://img.shields.io/github/issues/RATR2/repack?style=for-the-badge&logo=github)](https://github.com/RATR2/repack/issues)
[![Loaders](https://img.shields.io/badge/loaders-paper%20%7C%20purpur-blue?style=for-the-badge)](#install)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.4%20to%2026.3-blue?style=for-the-badge)](#install)
[![Clients](https://img.shields.io/badge/clients-1.21.4%20to%2026.3-blue?style=for-the-badge)](#install)
[![License](https://img.shields.io/github/license/RATR2/repack?style=for-the-badge&logo=github)](LICENSE)
[![Status](https://img.shields.io/badge/status-in--development-yellow?style=for-the-badge)](#status)

> Rendering of Entities, Post-effects, & Advanced Customization Kit

If you find any issues, please report them [here](https://github.com/RATR2/repack/issues)

</div>

---

## What is this

Repack is a customization kit for what players see and use: custom blocks and items made from Blockbench models, world-anchored ripple effects and screen shake, skyboxes from pictures or shaders, animated text colors, and more to come (the roadmap is in the repository). Everything is built into one resource pack per client version.

**Visual effects**
- **Ripples.** A shockwave that spreads out from a spot in the world and bends the screen.
- **Screen shake.** Shake a player's screen for explosions, hits or anything else, and let it fade out.
- **Skyboxes.** Replace the sky with a panorama picture or a small shader (stars, clouds and a gradient come with Repack). Choose where each one shows: by world, dimension or biome.
- **Animated text.** Text in certain colors shines, pulses, waves or turns rainbow, in chat, signs and item names.

**Custom blocks and items**
- **Blocks from Blockbench models.** Drop in a `.bbmodel` and make a block from it. Mining time, drops, tools, sounds and more are all settings.
- **Animated models.** Animations made in Blockbench play on blocks, and a block can play one when it is placed, when it idles, and when it is broken.
- **Custom items** with their own name, lore, model and shine.

Blocks use no entities on the server, are saved with the world, and work with protection plugins like WorldGuard.

Everything is set up in the game with `/repack` (screens for blocks and items, and for the server settings) and in files you can edit. Server owners: read the [guide](guide.md). Scripters and plugin developers: [dev.md](dev.md) covers Skript and the Java API.

Every commit on `main` is built and published to [RATR2/repack-nightly](https://github.com/RATR2/repack-nightly).

## Install

You need a Paper (or Purpur) server on 1.21.4 or newer, and the [PacketEvents](https://github.com/retrooper/packetevents) plugin (the Spigot jar, 2.14.0 or newer). Players can join on any version from 1.21.4 to 26.3. [ViaVersion](https://github.com/ViaVersion/ViaVersion) is optional, and lets players on versions other than the server's join too.

1. Put `repack.jar` and the PacketEvents jar in `plugins/` and start the server.
2. In `plugins/Repack/config.yml`, set `resource-pack.public-host` to your server's address, so players can download the pack.
3. Join, and run `/repack give table` to get the example block. Place it.
4. Add your own models to `plugins/Repack/models/` and make blocks from them in `/repack` > Block builder.

The [guide](guide.md) explains the rest: building and sending the pack, block and item options, effects, and troubleshooting.

## Status

In development. Built and unit tested, but expect rough edges.

- Models support cubes and bones. Meshes are skipped.
- Some effects depend on the player's graphics settings or version, which the [guide](guide.md) lists for each one.

## License

See [LICENSE](LICENSE). MIT. The model parsing and pack generation are adapted from [BetterModel](https://github.com/toxicity188/BetterModel), also MIT, see [NOTICE](NOTICE).

---

<div align="center">

Thanks, R4T Out.

</div>
