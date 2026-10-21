# Repack guide

Repack adds visual effects (ripples, screen shake, skyboxes, animated text) and Blockbench-made custom blocks and items to a Paper server. Players need no mod, only the resource pack Repack builds and sends. This guide is for server owners. For Skript and Java, see [dev.md](dev.md).

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
2. Put the `.bbmodel` file in `plugins/Repack/models/`. The file name is the model's id: `chair.bbmodel` is the model `chair`. Use a-z, 0-9, `_` and `-`; other characters in a file name become `_`. A model is only a model: it is not a block yet.
3. Save the file. A few seconds later Repack rebuilds the pack, quietly. New players get it when they join, and `/repack reload` or `/repack push` sends it to the ones online. Nothing needs a restart.
4. Make a block from it: `/repack` > Block builder > New block, pick an id and the model. Then `/repack give chair`, and place it.

The builder writes `plugins/Repack/blocks/chair.yml` with every option. You can also write that file by hand, and several blocks can share one model. If you would rather have every model be a block at once, set `auto-blocks: true` in `config.yml`.

What Blockbench features carry over:
- Cubes with their faces, textures and UV, and the Display tab (hand, inventory, ground, item frame, head, shelf). A model that only uses these is a plain vanilla block model, which every client from 1.21.4 shows.
- Rotated groups, cubes rotated freely on any axis or by any angle, cubes reaching outside -16 to 32, and animations work by splitting the model into bones (see [Bones and animations](#bones-and-animations)). Meshes are skipped with a warning.
- Animated textures and other element types (locators, null objects) are not supported yet and are skipped with a warning.

## The pack: build and send

Repack rebuilds the pack by itself and only sends it when you say so:

- **Saving a model or block file** rebuilds the pack a few seconds later. It is silent: nobody online is sent anything, and only the console and the operators' progress bar show it.
- **`/repack push`** sends the pack to everyone online, and **`/repack push <player>`** sends it to one player. It publishes the latest build first.
- **`/repack reload`** reads the block files, rebuilds the pack and pushes it to everyone online, unless it came out the same as the pack last pushed.
- **A player who joins** gets the newest build, even one that was never pushed. Set `resource-pack.join-latest-build: false` to give joiners only the pack that was last pushed.
- A build with errors is never used. The previous build stays, and the errors are in the chat and the console.
- **While a pack loads:** the screen freezes while a client reloads a pack. From the moment the client reports it has downloaded a pack Repack just sent it (a push or a join) until it has loaded it, for 20 seconds at most, the player is held. They cannot be hurt and cannot move (the server puts their position back, they are held in the air without falling, and they are allowed to hover so the server does not kick them for flying). They cannot attack, use items, break, place, drop or interact with anything. They see no other entities at all, and every block around them, air included, looks like black concrete (all but the two they stand in). All of it is sent to that player only, and the real blocks and entities come back when it is done. The same player cannot be held again for two minutes, so a modified client that fakes its status gains only a few seconds of standing still. Their normal settings are put back when it ends, if they leave, or if the server stops. Turn it off with `resource-pack.invulnerable-while-loading`, the black blocks with `resource-pack.black-blocks-while-loading`, and change the size with `resource-pack.loading-radius`.

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

`/repack` alone opens the settings screens for everyone: personal settings, and for those with `repack.settings` the server settings. Every other command needs `repack.admin` (given to operators by default); `/repack distortion` needs `distortion.spawn` or `distortion.debug`. `/give` also works with Repack's ids.

| Command | |
|---|---|
| `/repack reload [blocks\|config\|host\|all]` | With no argument: reads the block files, rebuilds the pack and pushes it to everyone online. `blocks` only reads the block files, `config` the config, `host` restarts the pack server (use it after changing the port), `all` does all of it. |
| `/repack build [force\|fresh]` | Only rebuilds the pack. It refuses when a model has errors; `force` builds without those models. `fresh` converts every model again instead of using the cache. |
| `/repack push [players]` | Publishes the latest build and sends it to the players you name, or to everyone online. They cannot be hurt until it has loaded. |
| `/repack status` | Built and published packs per version, and the pack server. |
| `/repack give <id> [amount] [players]` | Gives the item that places a block. |
| `/give @s repack:<id> [count]` | The same through vanilla `/give`, with tab completion. |
| `/repack builder [id]` | The block builder: edit a block's settings, item and recipe in screens (see [The block builder](#the-block-builder)). |
| `/repack menu` | A screen with every block item. Click to take one, shift-click for 64. |
| `/repack place <id> [pos]` and `/repack remove [pos]` | Place or remove a block without an item. |
| `/repack list` | The ids that can be placed. |
| `/repack animate <animation\|none> [loop\|once\|hold\|default] [pos]` | Makes the block you look at (or at pos) play an animation from the start, for testing. |
| `/repack distortion spawn <hex> <radius> <ticks> [pos]` | A ripple of that color growing to that radius in blocks over that many ticks. Also `/distortion ...`. |
| `/repack shake <players> <strength> <ticks> [rate balance roll]` | Shakes the screen of those players, fading out. 1.21.6 and newer clients with the pack. |
| `/repack skybox set\|off\|clear <players> [id]`, `/repack skybox list` | Choose the skybox of players, or list the skyboxes (see [Skyboxes](#skyboxes)). |
| `/repack distortion debug [on\|off]` | Shows outlines and the raw data of the ripples. 26.3 and newer clients only. |

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

### The block builder

`/repack builder` opens a list of every block, and `/repack builder <id>` opens one block. Everything in the block file can be changed there, and the same checks as for a hand-edited file apply. A block is written to `blocks/<id>.yml` only when you press **Save**, and that block is reloaded at once. Changes you have not saved are kept until you save, press "Throw away changes", or leave the server.

- **New block:** the "New block" button asks for an id (an anvil screen: type the name, then take the result), then shows the models in `models/` to pick one.
- **Numbers and choices:** buttons. Click adds or switches to the next, right click goes back, shift makes the step bigger.
- **Drops:** click an item in your own inventory to add a drop. Click raises the chance, shift click the amount, and Q removes it.
- **The exact item:** in the Item screen, put an item on your cursor and click the slot, or shift click it in your inventory. It is saved exactly, with its name, lore, enchantments and any custom data, and `item.stack` in the file holds it. "Use the block's model" says whether Repack puts the block's model on it or leaves the model the item already has, for example one from another pack.
- **The recipe:** a 3x3 grid like a dropper. Put an item on your cursor and click a slot, or shift click an item in your inventory to fill the next free slot. Custom items are kept exactly (`recipe.exact` in the file), and "Exact items" does the same for plain ones. The result slot sets how many it makes.

Only operators (`repack.admin`) can open it. With pictures for the screens (see dev.md) they look like custom screens; without them they are plain chests with vanilla items, which work the same.

### How a block behaves

- The model is a packet-only item display. Nothing is spawned on the server and nothing is saved in the world for it. Placed blocks are stored in the chunk data and survive restarts.
- Players mine and click the **proxy block**, a normal block that is hidden under the model. `BARRIER` is solid. `LIGHT` and `STRUCTURE_VOID` can be walked through.
- Models are shown to a player only while that player has the block's chunk loaded, and the client stops drawing them past `view-range`.
- Mining and reach follow the player's block interaction range. Creative players break blocks instantly.
- Changing `proxy.block` of a block that is already placed moves the placed blocks over to the new proxy as their chunks load.
- WorldGuard is checked when someone starts mining, breaks or clicks, and when placing. A denied action shows no mining progress and does nothing.

## Sending the pack through another plugin

By default Repack hosts the pack itself and sends it (`resource-pack.public-host` and `port`). If you already use a plugin that sends a resource pack, such as ItemsAdder or CraftEngine, set `resource-pack.send: false` and restart. Repack then starts no pack server and sends nothing; each build is written to `plugins/Repack/export/` (or the folder in `resource-pack.export`) as `repack.zip`, with a file per client version next to it (`repack-26.3.zip` and so on). Add `repack.zip` as an external zip in that plugin's settings, which is how BetterModel is added too, and it sends it with its own pack. `resource-pack.export-era` says which client version `repack.zip` is made for, 26.3 and newer by default; the sending plugin sends one pack to everybody, so pick the version most of your players use.

With `send: true` you can still set `resource-pack.export` to also write the zips. Ripples need to know when a player's client has loaded the pack: when Repack does not send it, any server pack a client loads counts.

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
| `auto-blocks` | `false` | Every model is also a block, with a `blocks/<id>.yml` written for it. Off: models are blocks only when you make one. |
| `messages.no-permission` | text | Sent when a protection plugin says no. Empty for no message. |
| `resource-pack.public-host` / `port` / `bind` | empty / `8086` / `0.0.0.0` | Where the pack server listens and what players are told. |
| `resource-pack.share-host` | `true` | Share the pack server with other plugins that use the same scheme. |
| `resource-pack.join-delay-ticks` | `40` | Wait after a player joins before sending the pack. |
| `resource-pack.join-latest-build` | `true` | Joiners get the newest build. `false`: only the pack last pushed. |
| `resource-pack.invulnerable-while-loading` | `true` | Players are held (safe, still, no entities visible) while a pack loads. |
| `resource-pack.black-blocks-while-loading` | `true` | The blocks around them look black while it loads. |
| `resource-pack.loading-radius` | `8` | How far around the player, up to 16. |
| `resource-pack.send-gap-ticks` | `20` | Minimum time between packs from different plugins to one player. |
| `resource-pack.required` / `prompt` | `false` / text | Kick players who decline, and the text of the prompt. |
| `client-notices` | `true` | Tell players their client is too old. |
| `recommend-latest` | `true` | The console recommends 26.3+ when the server runs something older. |
| `distortion.enabled` | `true` | The ripple effects: their shaders go into the pack and the effects are available. Needs a restart. |
| `text-effects.*` | two effects | Animated text, see [Animated text](#animated-text). |
| `workflow.*` | see above | Automatic rebuilding and the progress bar. |
| `gui.textures` | `true` | Use the pictures for the builder screens when there are any. |

Every settings file starts with `config-version` and a note not to edit it. When a new Repack version changes the shipped `config.yml` or the block template, the file is updated on the next start: new options are added, your values and comments are kept, and the old file is saved as `<name>.bak-<version>` if anything besides the version changed.

## Bones and animations

A model that a single vanilla model cannot hold (it has animations, a rotated group, a cube rotated on several axes or by an odd angle, or a cube outside -16 to 32) is split into **bones**, the way BetterModel does it. Every group of the outliner is a bone, and so is every cube with a rotation vanilla cannot draw. The pack gets one small vanilla model for each bone, and the game shows one item display for each: nothing but plain item models, so it needs no shaders and works on every client from 1.21.4 on. Rotations and animations only change the transform of each display. The model you hold or see in an inventory is still the single plain model (without the rotations).

Animations are the Blockbench ones, and each repeats the way Blockbench says: `loop` over and over, `once` one time and then settles back into the pose the model is built in (over a quarter of a second), and `hold` one time and then stays in its last pose. A block file can override that with `display.animation-mode` (`loop`, `once` or `hold`), and the API, the command and Skript can too. Bones are the groups of the outliner; position, rotation and scale keyframes with linear, catmull-rom and step curves are used (bezier handles are treated as catmull-rom, and Molang expressions count as 0). ### Idle, place and break

Three animation names do something on their own. An animation called **idle** loops all the time (`display.animation` can name another one instead, and `display.animation-mode` changes how it repeats; a stopped idle stays stopped until the block is shown again). An animation called **place** plays once when the block is placed, and the block cannot be broken (or blown up) until it is over, then idle starts. An animation called **break** plays when a player breaks the block, before it goes (`display.break-animation: delay`, the default): the block is held in its last pose and goes when the animation ends. With `linger` the block goes at once and only its model stays for the animation, which is also what happens when something other than a player breaks it (an explosion, a piston, creative mode); `off` skips it. A block with none of these just shows the model as built.

A block plays one all the time when `display.animation` in its block file is the animation's name in lowercase (`Spin Fast` is `spin_fast`), and every copy of a block plays in step. Set from a script or another plugin, `RepackAPI.playAnimation` and the Skript effect `play animation "spin" on custom block at ...` start one on a single block (and `stop the animation of custom block at ...` stops it); that is not saved. Only blocks use bones: the model in a hand or an inventory is the single plain model, and nothing is drawn as a separate entity. Animations are sent about ten times a second, and the client blends between the steps.

Limits: the scale of the block is applied on top of the bones, which is exact for an even scale only, and a model with many bones sends more packets while it plays.

## Custom items

An item is a file in `items/` (`items/ruby.yml`), made like a block: `base` (the vanilla item), `model` (a `.bbmodel` in `models/`, optional), `name` and `lore` (with `&c` codes and `<#rrggbb>` colors), `glint`, and for developers `components` (raw item components as in `/give`, like `[minecraft:custom_data={a:1}]`). A saved exact item can also be the item: it decides everything but the model. A model that only items use does not turn into a block. Players get items the way they get blocks: `/repack give ruby`, `/give @s repack:ruby`, the `/repack menu` screen, Skript and the Java API.

Edit them in the game with `/repack` > Item builder: pick an item or make a new one, then set the base item (click an item in your inventory), name, lore, shine, model, exact item and components, and press Save. In the block builder, right click the Item button of a block to make the block use a registered item (`item.registered: ruby` in the block file); the block then places itself from that item. Or leave it alone and use the block's own item settings, or an exact item.

Text typed into the anvil keeps the `&` and `<#rrggbb>` color codes.

## Animated text

Text in certain colors can move: a shine sweeping along it, a pulse, a wave, a strobe or a rainbow. It is the shader pack r4tshader, built into Repack. Under `text-effects.effects` in `config.yml` each effect has a name, a `trigger` color and a `from` and `to` color for the gradient, a `type` (`shine`, `pulse`, `wave`, `strobe` or `rainbow`) and tuning numbers (`speed`, `frequency`, `bias`, `hot-exponent`, `hot-strength`, `offset`); `text-effects.defaults` fills in what an effect leaves out. Any text drawn in the trigger color is animated, in chat, on signs, in item names and anywhere else, and so is its drop shadow. Use the color directly (`&#ff0cff` or `<#ff0cff>`) or the effect's name, `<fx:pink>`, in an item or block name or lore. It works for 1.21.6 and newer clients and goes into the pack at the next build; `enabled: false` leaves it out. The two effects that ship are `pink` (`#ff0cff`) and `teal` (`#0e5e6e`).

Nametags and other see-through text are not animated.

## Ripple effects

A ripple spawned at a spot in the world distorts the screen of players who have the pack, in the color and size you give. From 26.3 on it works with any graphics setting; on 1.21.4 to 26.2 players see it only with Fabulous! graphics. Spawn one with `/repack distortion spawn 55CCFF 8 40` (color, radius in blocks, ticks), from Skript with `spawn a ripple at ...`, or from Java with `RepackAPI.ripple`. Up to 256 ripples show at once. The pack has one build per client version, because the shaders differ in every game version. If a separate Distortion plugin is installed, Repack says so and keeps its own ripples off until you remove it.

## Screen shake

Part of the same effects as the ripples: `/repack shake <players> <strength> <ticks> [rate balance roll]` shakes the screen of the players you name. Strength is 0 to 1 and the shake fades out over the ticks (strength is squared, so a weak shake stays gentle). The optional numbers, each 0 to 1, say how fast it jitters (`rate`), how much of it is up and down rather than sideways (`balance`, 0.5 is even) and how much it turns the picture (`roll`). A second shake while one runs only replaces it when it is at least as strong as what is left. It works for 1.21.6 and newer clients that have the pack loaded; on 1.21.6 to 26.2 players see it only with Fabulous! graphics, and from 26.3 on the pack adds the shake as a post effect only while a player shakes. From Skript: `shake player's screen with strength 0.5 for 40 ticks`, `stop shaking player's screen`; from Java: `RepackAPI.shake(player, 0.5, 40)`. It needs the `distortion.shake` permission (operators have it) and switches off with `distortion.enabled`.

## Skyboxes

A skybox replaces the sky around a player: a picture of the whole sky wrapped around them, or a small piece of GLSL that works out the color of every direction. Skyboxes are files in `plugins/Repack/skyboxes/`, and only players with the pack on a 1.21.6 or newer client see them (at most 24 can exist). `_example.yml` in that folder explains every option; the short version:

1. Put `forest.png` (a panorama of the whole sky, twice as wide as it is high) in the folder, and write `forest.yml` next to it. An empty file is enough: it uses `forest.png`, or else `forest.glsl`. For a sky that comes with Repack write `source: builtin:stars` (the others are `gradient` and `clouds`).
2. Say where it shows with `when:` (`dimensions`, `worlds`, `biomes`), how it ranks with `priority`, and who sees it with `permission`. A skybox shows for a player when everything in `when` that is not empty fits where they are; if several fit, the highest priority wins, then the one that names more. The check runs when they join, change world or biome.
3. `/repack build`.

A GLSL sky is only the statements that end by setting `color`, with the direction `dir`, the time of day `skyTime`, the player's height `skyHeight`, a clock `skyAnim` and helpers such as `skyGradient`, `skyNoise`, `skyFbm`, `skyStars` and `skySunDirection` (the list is in `_example.yml`). Your GLSL is only checked by the player's game when it loads the pack, so try a new sky on one client first.

Make someone see a particular one with `/repack skybox set <players> <id>`, `off <players>` for none, `clear <players>` to go back to the one that fits, and `list`. Not saved: it lasts until they leave. From Skript: `set player's skybox to "stars"`, `hide player's skybox`, `reset player's skybox`.

How it works: each player gets a display that only they see (nothing is spawned on the server), scaled inside out around them and sent as a passenger of their own entity, so it moves with them exactly. The pack replaces the game's item shader with the same one plus a branch that draws a sprite whose alpha is a sky code as a sky; it changes nothing for any other item, but another pack that replaces that same shader would clash with it. The time of day and the height reach the sky through the color of the item. The idea is [SkyboxEngine](https://github.com/Dragoncraft000/SkyboxEngine)'s (MIT, in NOTICE).

## Troubleshooting

- **A player did not get the pack.** `/repack status` should list a published pack. Check `public-host`, the port, and the console for "Could not open the pack server". The player's client must be 1.21.4 or newer.
- **`/repack list` shows nothing.** Only `.bbmodel` files directly in `models/` count. Look at the console for errors with the file name.
- **"Build rejected".** One or more models have errors, listed in chat and the console. Fix them, or use `/repack build force` to build without those models.
- **A model looks wrong.** Warnings on build list what was dropped, such as an unsupported rotation. Textures must be embedded in the `.bbmodel`.
- **The block takes too long or too short to mine.** `breaking.time` only applies with `proxy.mode: custom`. In `vanilla` mode the proxy block's speed is used.
- **Clicking the block does the proxy's job (a menu opens).** Set `proxy.vanilla-interaction: false`, which is the default.
- **Something else is wrong.** Set `debug: true`, repeat the action, and read the console.
