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

## Pack hosting for other plugins

A plugin that sends its own server resource pack can share Repack's web server and the single join prompt. Repack and Distortion do this through a Bukkit service of type `java.util.Map` made only of JDK types, because each plugin has its own copy of every class. The protocol is in `SharedHost.kt` (identical in both plugins, apart from the package line): a plugin registers a name for its files and an offer function for joining players, and the plugin that started first hosts for all of them.
