# Repack guide

Repack turns Blockbench models into custom blocks on a Paper server. Players need no mod, only the resource pack Repack builds and sends. This guide is for server owners. For Skript and Java, see [dev.md](dev.md).

## What you need

- Paper 1.21.4 or newer. Repack is built for Java 21, so it runs on the Java 21 of a 1.21.x server and on newer Java.
- The [PacketEvents](https://github.com/retrooper/packetevents) plugin (the Spigot jar, 2.14.0 or newer). Repack does not start without it.
- Optional: [ViaVersion](https://github.com/ViaVersion/ViaVersion) (and ViaBackwards) so players on other client versions get the right pack, WorldGuard for protected regions, Skript for scripting.

Clients from 1.21.4 to 26.3 are supported. Older clients cannot show the models and get a short notice in chat.

## Install

1. Put `repack.jar` and the PacketEvents jar in `plugins/` and start the server.
2. The first start adds a ready-made `table` block (`models/table.bbmodel` and `blocks/table.yml`). Set `install-defaults: false` in `config.yml` before the first start to skip it.
3. Set `resource-pack.public-host` in `plugins/Repack/config.yml` to your server's address (see [Pack hosting](#pack-hosting)).
4. Wait a few seconds for the first pack to build, then join (or rejoin) to get it, run `/repack give table` and place it.

## Making a block

1. Make a model in Blockbench (Java Block/Item model) and **embed the textures** when you save it.
2. Put the `.bbmodel` file in `plugins/Repack/models/`. The file name is the block id: `chair.bbmodel` makes the block `chair`. Use a-z, 0-9, `_` and `-`; other characters in a file name become `_`.
3. Save the file. A few seconds later Repack rebuilds the pack, quietly. New players get it when they join, and `/repack reload` or `/repack push` sends it to the ones online. Nothing needs a restart.
4. `/repack give chair`, place it.

Repack also writes `plugins/Repack/blocks/chair.yml` with every option at its default. Edit that file to change how the block behaves.

What Blockbench features carry over:
- Cubes with their faces, textures and UV, and the Display tab (hand, inventory, ground, item frame, head, shelf).
- A cube can rotate around one axis by -45, -22.5, 22.5 or 45 degrees. Other rotations are dropped and listed as warnings.
- Cubes may reach from -16 to 32 on each axis. A model with a cube outside that is an error, because the game would not load it, and it is left out of the pack.
- Rotated groups, meshes and animated textures are not supported yet and are skipped with a warning.

## The pack: build and send

Repack rebuilds the pack by itself and only sends it when you say so:

- **Saving a model or block file** rebuilds the pack a few seconds later. It is silent: nobody online is sent anything, and only the console and the operators' progress bar show it.
- **`/repack push`** sends the pack to everyone online, and **`/repack push <player>`** sends it to one player. It publishes the latest build first.
- **`/repack reload`** reads the block files, rebuilds the pack and pushes it to everyone online, unless it came out the same as the pack last pushed.
- **A player who joins** gets the newest build, even one that was never pushed. Set `resource-pack.join-latest-build: false` to give joiners only the pack that was last pushed.
- A build with errors is never used. The previous build stays, and the errors are in the chat and the console.
- **While a pack loads:** the screen freezes while a client reloads a pack. From the moment the client reports it has downloaded a pack Repack just sent it (a push or a join) until it has loaded it, for 20 seconds at most, the player is held. They cannot be hurt and cannot move (the server puts their position back, they are held in the air without falling, and they are allowed to hover so the server does not kick them for flying). They cannot attack, use items, break, place, drop or interact with anything. They see no other entities at all, and the blocks around them look like black concrete. All of it is sent to that player only, and the real blocks and entities come back when it is done. The same player cannot be held again for two minutes, so a modified client that fakes its status gains only a few seconds of standing still. Their normal settings are put back when it ends, if they leave, or if the server stops. Turn it off with `resource-pack.invulnerable-while-loading`, the black blocks with `resource-pack.black-blocks-while-loading`, and change the size with `resource-pack.loading-radius`.

Two settings under `workflow:` in `config.yml` control the building:

| Key | Default | |
|---|---|---|
| `build-on-change` | `true` | Rebuild a few seconds after a model or block file is saved. `false` leaves it to `/repack reload` and `/repack build`. |
| `progress-bar` | `true` | Show operators the build progress. |

`/repack build` only builds. It sends nothing and publishes nothing, so you can check a build and then `/repack push` it.

Repack builds one pack per client version range (`legacy` for 1.21.4 to 1.21.8 and `modern` for 1.21.9 and newer, the older one without the shelf display setting) and sends each player the one that fits their client. `/repack status` shows both.

Converted models are cached in `plugins/Repack/cache/models/`, one file per model, named by the model's id and a hash of its file. A build only converts the models that changed, and the first build after a restart reuses the cache too. The cache is thrown away when Repack's version changes. If a model looks stale anyway, for example on a development build with an unchanged version number, run `/repack build fresh`, or delete that folder.

If Distortion is installed too, both plugins send their packs in one request when a player joins, so there is a single "accept the resource pack" prompt.

## Commands

All of them need `repack.admin` (given to operators by default). `/give` also works with Repack's ids.

| Command | |
|---|---|
| `/repack reload [blocks\|config\|host\|all]` | With no argument: reads the block files, rebuilds the pack and pushes it to everyone online. `blocks` only reads the block files, `config` the config, `host` restarts the pack server (use it after changing the port), `all` does all of it. |
| `/repack build [force\|fresh]` | Only rebuilds the pack. It refuses when a model has errors; `force` builds without those models. `fresh` converts every model again instead of using the cache. |
| `/repack push [players]` | Publishes the latest build and sends it to the players you name, or to everyone online. They cannot be hurt until it has loaded. |
| `/repack status` | Built and published packs per version, and the pack server. |
| `/repack give <id> [amount] [players]` | Gives the item that places a block. |
| `/give @s repack:<id> [count]` | The same through vanilla `/give`, with tab completion. |
| `/repack menu` | A screen with every block item. Click to take one, shift-click for 64. |
| `/repack place <id> [pos]` and `/repack remove [pos]` | Place or remove a block without an item. |
| `/repack list` | The ids that can be placed. |

## Block files

`blocks/<id>.yml` holds the settings of one block. Every key is optional, and `blocks/_example.yml` (rewritten on every start) explains each one. The most used:

```yaml
model: chair            # the .bbmodel to show; two blocks can share one model

proxy:
  block: BARRIER        # the invisible real block under the model: what players click and collide with
  mode: custom          # custom: breaking.time sets the mining time. vanilla: the proxy block's own speed
  vanilla-interaction: false   # true keeps what the proxy block does on right click (a smithing table's menu)

breaking:
  time: 1.5             # seconds
  tool: pickaxe         # any, pickaxe, axe, shovel, hoe, sword, shears
  tier: stone           # weakest tool material that gets drops
  drops-need-tool: true
  xp: 0

drops:
  - block: self
  - item: DIAMOND
    amount: 1-2
    chance: 0.25

explosion: {resistant: false, drops: false}
piston: block           # block (will not move) or destroy

display:
  offset: [0, 0, 0]
  scale: [1.0, 1.0, 1.0]      # one number or x, y, z
  view-range: 1.0             # multiplies the 64 block draw distance, up to 100
  brightness: 15              # leave out to light it like the world around it
  rotate-to-placer: false
  context: none               # which Display tab setting the placed block uses

item:
  base: PAPER
  name: "&bChair"
  lore: ["&7Sit down."]
  glint: false
  model: chair_item           # a different model for the item than for the block

recipe:
  shape: ["aba", "bcb", "aba"]
  ingredients: {a: AMETHYST_SHARD, b: DIAMOND, c: SMITHING_TABLE}
  amount: 1
```

To make a second block from the same model, copy the file (`chair2.yml`) and keep `model:` pointing at the same `.bbmodel`.

Edits to a block file are picked up a few seconds after you save it, and only that block is reloaded. A file with a mistake is reported with the file name and the problem, and that block is not loaded until it is fixed: blocks of that type that are already placed stay in the world but show nothing.

### How a block behaves

- The model is a packet-only item display. Nothing is spawned on the server and nothing is saved in the world for it. Placed blocks are stored in the chunk data and survive restarts.
- Players mine and click the **proxy block**, a normal block that is hidden under the model. `BARRIER` is solid. `LIGHT` and `STRUCTURE_VOID` can be walked through.
- Models are shown to a player only while that player has the block's chunk loaded, and the client stops drawing them past `view-range`.
- Mining and reach follow the player's block interaction range. Creative players break blocks instantly.
- Changing `proxy.block` of a block that is already placed moves the placed blocks over to the new proxy as their chunks load.
- WorldGuard is checked when someone starts mining, breaks or clicks, and when placing. A denied action shows no mining progress and does nothing.

## Pack hosting

Repack runs a small web server for the pack, by default on port **8086**. Players' clients download from it, so:

1. Set `resource-pack.public-host` in `config.yml` to your server's public address. If it is empty and `server-ip` is empty too, players are told to download from `localhost`, which cannot work, and the log warns about it.
2. Make sure `resource-pack.port` is open to players in your firewall.
3. After changing the port or address run `/repack reload host`. No restart is needed.

If Distortion (or another plugin that uses the same scheme) is installed, the first plugin that starts hosts the server for all of them, and the others serve their packs under it. Turn that off with `resource-pack.share-host: false`.

## config.yml

| Key | Default | |
|---|---|---|
| `debug` | `false` | Logs clicks and mining, to find out why something does not work. |
| `install-defaults` | `true` | Install the bundled table the first time. |
| `create-block-files` | `true` | Write `blocks/<id>.yml` for every model that has none. |
| `messages.no-permission` | text | Sent when a protection plugin says no. Empty for no message. |
| `resource-pack.public-host` / `port` / `bind` | empty / `8086` / `0.0.0.0` | Where the pack server listens and what players are told. |
| `resource-pack.share-host` | `true` | Share one pack server with Distortion. |
| `resource-pack.join-delay-ticks` | `40` | Wait after a player joins before sending the pack. |
| `resource-pack.join-latest-build` | `true` | Joiners get the newest build. `false`: only the pack last pushed. |
| `resource-pack.invulnerable-while-loading` | `true` | Players are held (safe, still, no entities visible) while a pack loads. |
| `resource-pack.black-blocks-while-loading` | `true` | The blocks around them look black while it loads. |
| `resource-pack.loading-radius` | `8` | How far around the player, up to 16. |
| `resource-pack.send-gap-ticks` | `20` | Minimum time between packs from different plugins to one player. |
| `resource-pack.required` / `prompt` | `false` / text | Kick players who decline, and the text of the prompt. |
| `client-notices` | `true` | Tell players their client is too old. |
| `workflow.*` | see above | Automatic rebuilding and sending. |

Every settings file starts with `config-version` and a note not to edit it. When a new Repack version changes the shipped `config.yml` or the block template, the file is updated on the next start: new options are added, your values and comments are kept, and the old file is saved as `<name>.bak-<version>` if anything besides the version changed.

## Troubleshooting

- **A player did not get the pack.** `/repack status` should list a published pack. Check `public-host`, the port, and the console for "Could not open the pack server". The player's client must be 1.21.4 or newer.
- **`/repack list` shows nothing.** Only `.bbmodel` files directly in `models/` count. Look at the console for errors with the file name.
- **"Build rejected".** One or more models have errors, listed in chat and the console. Fix them, or use `/repack build force` to build without those models.
- **A model looks wrong.** Warnings on build list what was dropped, such as an unsupported rotation. Textures must be embedded in the `.bbmodel`.
- **The block takes too long or too short to mine.** `breaking.time` only applies with `proxy.mode: custom`. In `vanilla` mode the proxy block's speed is used.
- **Clicking the block does the proxy's job (a menu opens).** Set `proxy.vanilla-interaction: false`, which is the default.
- **Something else is wrong.** Set `debug: true`, repeat the action, and read the console.
