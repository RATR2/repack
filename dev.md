# Repack for developers

How to use Repack from Skript and from Java. For running and configuring it, see [guide.md](guide.md).

Repack's blocks are identified by an **id**: the model file name, cleaned to a-z, 0-9, `_` and `-` (`models/chair.bbmodel` is `chair`). The ids you can use are the ones in `/repack list`.

## Skript

Needs Skript 2.13 or newer. Repack adds its syntax when it starts, using the new registration API on Skript 2.14 and newer and the old one before that. If that fails, the log says so and the rest of Repack still works.

### Conditions, expressions and effects

| Syntax | |
|---|---|
| `%blocks% is a custom block [with id %string%]` | Condition. Also `is not` / `isn't`, `are`, `aren't`. |
| `custom block id of %block%` or `%block%'s custom block id` | Expression: the id, or nothing when the block is not a custom block. |
| `custom block item %string% [x %number%]` | Expression: the item that places the block, as an item. `times` works too. |
| `all custom block ids` | Expression: every id that can be placed. |
| `place custom block %string% at %locations% [facing %number%]` | Effect. `facing` is a yaw in degrees, used by blocks with `display.rotate-to-placer`. |
| `remove custom block at %locations%` | Effect. Removes the block and its model without drops. |
| `register custom block %string% [using model %string%]` | Effect. Adds a block type with the default settings until the next reload. |
| `unregister custom block %string%` | Effect. Removes a block type until the next reload. Placed blocks stay in the world and show nothing. |
| `reload custom blocks` | Effect. Reads the block files again, which also undoes `register` and `unregister`. |

```
command /table:
    permission: example.table
    trigger:
        give player custom block item "table" x 2
        set {_spot} to 2 meters in front of player
        place custom block "table" at {_spot} facing player's yaw
        if player's target block is a custom block with id "table":
            send "You are looking at a table." to player
```

### Items

`RepackAPI.item(id, amount)` gives the stack for a block or custom item id. `itemIds()`, `itemIdOf(stack)`, `registerItem(id, "EMERALD", model)` and `unregisterItem(id)` (until the next reload), and `createItem(id)` / `editItem(id)` return an `ItemBuilder`: chain `base`, `model`, `name`, `lore`, `glint`, `exact`, `components`, then `save()`, which writes `items/<id>.yml`. In Skript: `custom item "ruby"`, with `x 3` for a count.

### Ripples

`RepackAPI.ripple(location, 0x55CCFF, 8.0, 40)` spawns a ripple (color, radius in blocks, ticks), `activeRipples()` and `maxRipples()` count them. In Skript: `spawn a ripple at player's location with color "55CCFF" radius 8 lasting 40 ticks`.

### Events

`on custom block interact [of %strings%]` (or `click`) fires when a player clicks a custom block, with right or left click. Event values: `event-block`, `event-player`, `event-location`, `event-string` (the id).

```
on custom block interact of "table":
    cancel event
    send "The table hums." to player
```

For everything else that happens to a block there is one event per action, `on custom block <action> [of %strings%]`, with the same event values:

| Action | When |
|---|---|
| `break start` | A player starts mining it. Cancelling stops the mining. |
| `break` | A player is about to break it. |
| `explode` | An explosion would destroy it. |
| `piston` | A piston would push or pull it. |
| `burn` | Fire would burn it away. |
| `entity change` | An entity would change it (an enderman, a wither, a falling block). |
| `entity interact` | An entity touches it, such as a mob on a pressure plate. |
| `fade` | It would melt or decay. |
| `fluid` | A liquid would flow into it. Cancelled by default. |
| `bucket` | A bucket would be emptied into it or filled from it. Cancelled by default. |
| `grow` | A growing structure or bone meal would replace it. Cancelled by default. |
| `physics` | It would be destroyed because it lost its support. |
| `broken by block` | Another block broke it, such as flowing water. Cannot be cancelled. |

These events are already cancelled when the block resists the action by its settings (`piston: block`, `explosion.resistant: true`, the defaults above). `uncancel event` lets it through, and `cancel event` stops one that was allowed.

```
on custom block break start of "table":
    if player does not have permission "example.table.break":
        cancel event
        send "Only staff can take this apart." to player

on custom block explode of "table":
    uncancel event
```

`on right click` and the other normal events also see custom blocks, as the proxy block (a barrier by default). Use `event-block is a custom block with id "table"` to tell them apart. A complete example is in [examples/repack-example.sk](examples/repack-example.sk).

## Java

### Calling it

`io.github.ratr2.repack.RepackAPI` has static methods. Repack is not on a Maven repository: build against the jar from the releases (`compileOnly(files("libs/repack.jar"))`) and add `depend: [repack]` or `softdepend: [repack]` to your `plugin.yml`.

| Method | |
|---|---|
| `boolean place(Location, String id)` / `place(Location, String id, float yaw)` | Places a block. False if the id is unknown, the spot is taken or Repack is not running. |
| `boolean remove(Location)` | Removes the custom block there, without drops. False if there was none. |
| `String idAt(Block)` | The id of the custom block at a block, or null. |
| `boolean isCustomBlock(Block, String id)` | True when the block is a custom block, and with that id if `id` is not null. |
| `ItemStack item(String id, int amount)` | The item that places the block, or null for an unknown id. |
| `String register(String id, String model)` | Adds a block type that shows `models/<model>.bbmodel` until the next reload. Returns an error message, or null when it worked. |
| `boolean unregister(String id)` | Removes a block type until the next reload. |
| `List<String> reload()` | Reads the block files again; returns the errors found. |
| `Set<String> ids()` | The ids that can be placed right now. |

The methods may be called from any thread. Off the main thread they wait (up to 10 seconds) for the main thread to run them, because the block data is not thread safe. From the main thread they run at once.

Without a compile dependency, with reflection:

```java
Class<?> api = Bukkit.getPluginManager().getPlugin("Repack").getClass().getClassLoader()
    .loadClass("io.github.ratr2.repack.RepackAPI");
boolean placed = (boolean) api.getMethod("place", Location.class, String.class).invoke(null, location, "table");
```

### Events

Both are Bukkit events in `io.github.ratr2.repack.block`, fired on the main thread.

**`CustomBlockInteractEvent`** (cancellable) is fired when a player clicks a custom block. Cancelling it cancels the click.

| Getter | |
|---|---|
| `getPlayer()`, `getBlock()` | Who clicked and the proxy block. |
| `getId()` | The custom block's id. |
| `getAction()` | Bukkit's `Action` (`RIGHT_CLICK_BLOCK`, `LEFT_CLICK_BLOCK`, `PHYSICAL`). |
| `getHand()` | The hand, or null when there is none. |

**`CustomBlockEvent`** (cancellable) is fired for everything that happens to the proxy block, after the block's own settings are applied.

| Getter | |
|---|---|
| `getBlock()`, `getId()`, `getDefinition()` | The block, its id and its settings. |
| `getAction()` | A `BlockAction`: `BREAK_START`, `BREAK`, `EXPLODE`, `PISTON`, `BURN`, `ENTITY_CHANGE`, `ENTITY_INTERACT`, `FADE`, `FLUID`, `BUCKET`, `GROW`, `PHYSICS` or `BROKEN_BY_BLOCK`. The table above says what each one means. |
| `getPlayer()` | The player behind it, or null. |
| `getCause()` | The Bukkit event it came from, or null. |

It starts cancelled when the block resists the action, or by default for `FLUID`, `BUCKET` and `GROW`. Calling `setCancelled(false)` lets the action through. `BROKEN_BY_BLOCK` cannot be cancelled.

```java
@EventHandler
public void onCustomBlock(CustomBlockEvent event) {
    if (event.getAction() == BlockAction.EXPLODE && event.getId().equals("table")) {
        event.setCancelled(false); // tables can be blown up
    }
}
```

## The block builder from code

Everything the builder screens do is also here, and both use the same checks and the same files. `RepackAPI.edit(id)` opens an existing block and `RepackAPI.create(id, model)` starts a new one with the defaults. Chain the changes and finish with `save()`, which checks the block, writes `blocks/<id>.yml` and loads it at once. It returns what is wrong, or null when it worked.

```java
String error = RepackAPI.create("chair", "chair")
    .proxy("LIGHT").breakingTime(2.0).tool("axe").tier("stone")
    .clearDrops().dropBlock().dropItem(Material.STICK, 2, 0.5)
    .offset(0, 0.04, 0).scale(1.01, 1.08, 1.01).brightness(15)
    .item(myItem, true)                       // an exact item; true puts the block's model on it
    .recipe(Arrays.asList(a, b, a, null, b, null, null, null, null), false, 2)
    .save();
```

| Call | Sets |
|---|---|
| `proxy`, `mode`, `vanillaInteraction` | `proxy.*` |
| `breakingTime`, `tool`, `tier`, `dropsNeedTool`, `xp` | `breaking.*` |
| `clearDrops`, `dropBlock`, `dropItem` | `drops` |
| `explosionResistant`, `explosionDrops`, `piston` | `explosion.*`, `piston` |
| `offset`, `scale`, `viewRange`, `brightness`, `rotateToPlacer`, `context` | `display.*` |
| `itemBase`, `itemName`, `itemLore`, `glint`, `itemModel` | `item.*` |
| `item(stack, overrideModel)` | `item.stack` and `item.override-model`: the exact item, kept as it is |
| `recipe(grid, exact, amount)`, `clearRecipe` | `recipe.*`: a 3x3 grid row by row, null for empty. Custom items are exact, `exact` makes plain ones exact too |
| `set(path, value)` | Any key of the block file, such as `breaking.time` |
| `problem()`, `save()` | What is wrong, or null; and write it |

### From Skript

```
create custom block "chair" using model "chair"
set custom block "chair"'s option "breaking.time" to 2.5
set custom block "chair"'s option "display.scale" to 1, 1.2, 1
set custom block "chair"'s item to {_sword}
set custom block "chair"'s item to {_sword} without the block's model
set custom block "chair"'s recipe to {_a}, {_b}, {_a}, air, {_b}, air, air, air, air with amount 2
clear custom block "chair"'s item
clear custom block "chair"'s recipe
```

Each line saves the block at once. A problem is written to the console as a warning with the block's id.

## Art for the builder screens

The screens work without art. To give them custom backgrounds and icons, put PNG files in the plugin's resources and build:

- **Backgrounds:** `src/main/resources/gui/screens/<screen>.png` for `list`, `models`, `overview`, `proxy`, `breaking`, `drops`, `rules`, `display`, `item` and `recipe`. They reach 8 pixels beyond the screen on the left, right and top, and 4 below the chest part, so they are 192 pixels wide and 180 (3 rows) or 234 (6 rows) high, and must keep **transparent holes** where the items are, because the picture is drawn above them. Unused chest slots should be covered by the picture.
- **Icons:** `src/main/resources/gui/icons/<icon>.png`, 16x16, named as in the table below. A missing icon is the vanilla item named there. An icon can move: make the PNG a column of frames (16 wide and a multiple of 16 high) and add `<icon>.png.mcmeta` next to it with `{"animation": {"frametime": 4}}`, the same as any item texture.
- The files go into Repack's pack by themselves. A background is the title of the chest screen in the font `repack:gui`, positioned with an ascent of 13 + 8 (`GuiAssets.PAD`) and a space glyph that shifts it 16 pixels left. If a picture sits a few pixels off in the game, that offset is the place to change (`GuiPack`).
- Templates with the holes already cut, and numbered guides, are made by `.dev/tools/gui-templates.py` into `.dev/gui-templates/`.

### Icons the screens use

| Icon | Where | Vanilla item until there is a picture |
|---|---|---|
| `back` | Every Back button (all settings screens, the model list, the Overview's way to the block list) | arrow |
| `previous`, `next` | Page buttons of the block list and the model list | arrow |
| `close` | Close, in the block list | barrier |
| `new` | New block, in the block list | emerald |
| `save` | Save, in the Overview | lime dye |
| `reset` | Throw away changes (Overview), Back to the default (Drops), Clear the grid (Recipe) | red dye |
| `check`, `cross` | Every on and off button: vanilla interaction, drops need the tool, explosions, rotate to placer, shine, the block's model, exact items | lime dye, gray dye |
| `breaking`, `drops`, `rules`, `display`, `item`, `recipe` | The six settings buttons of the Overview (rules is explosions and pistons) | iron pickaxe, chest, TNT, item frame, name tag, crafting table |
| `mode` | Proxy mode | clock |
| `time`, `tool`, `tier`, `xp` | Breaking time, tool, tier and experience | clock, iron pickaxe, netherite ingot, experience bottle |
| `piston` | Piston behaviour | piston |
| `block`, `add` | Drops: drop the block itself, add a drop | chest, hopper |
| `offset`, `scale`, `view`, `light`, `context` | Display: offset, scale, view range, light and context | compass, spyglass, ender eye, light block, item frame |
| `name`, `lore` | The item's name and lore | name tag, writable book |
| `category` | The recipe's book category | knowledge book |

The Overview's proxy button shows the real proxy block, the item screen's slots show the real items, and the model list shows paper, so none of those take an icon.

The vanilla "Inventory" label is still drawn over the picture, since the game draws it after the title.

## Pack hosting for other plugins

A plugin that sends its own server resource pack can share Repack's web server and the single join prompt. Repack and Distortion do this through a Bukkit service of type `java.util.Map` made only of JDK types, because each plugin has its own copy of every class. The protocol is in `SharedHost.kt` (identical in both plugins, apart from the package line): a plugin registers a name for its files and an offer function for joining players, and the plugin that started first hosts for all of them.
