package io.github.ratr2.repack.gui

import io.github.ratr2.repack.block.AmountRange
import io.github.ratr2.repack.block.DisplayContext
import io.github.ratr2.repack.block.ToolTier
import io.github.ratr2.repack.block.ToolType
import io.github.ratr2.repack.edit.Draft
import io.github.ratr2.repack.model.ModelLibrary
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import kotlin.math.roundToInt

private const val PAGE = 45

private fun round(value: Double, places: Int = 2): Double {
    val factor = Math.pow(10.0, places.toDouble())
    return Math.round(value * factor) / factor
}

private fun <T> cycle(options: List<T>, current: T, step: Int): T {
    val at = options.indexOf(current).let { if (it < 0) 0 else it }
    return options[Math.floorMod(at + step, options.size)]
}

class ListScreen(gui: Gui, player: Player, private val page: Int = 0) : Screen(gui, player, 6, "list", "Repack blocks") {

    private val ids = gui.plugin.blocks.types.sorted()

    override fun render() {
        for ((slot, id) in ids.drop(page * PAGE).take(PAGE).withIndex()) {
            val item = gui.plugin.blocks.definitionOf(id)?.let { gui.plugin.items.create(it) } ?: ItemStack(Material.PAPER)
            put(slot, GuiAssets.decorate(item, id, listOf("Click to edit", "Shift right click to delete")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        button(47, "new", Material.EMERALD, "New block", "Pick an id, then a model")
        button(49, "close", Material.BARRIER, "Close")
        if ((page + 1) * PAGE < ids.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when {
            slot < PAGE -> ids.getOrNull(page * PAGE + slot)?.let {
                gui.openLater(player, if (event.click == ClickType.SHIFT_RIGHT) DeleteScreen(gui, player, it, page) else OverviewScreen(gui, player, it))
            }
            slot == 45 && page > 0 -> gui.openLater(player, ListScreen(gui, player, page - 1))
            slot == 53 && (page + 1) * PAGE < ids.size -> gui.openLater(player, ListScreen(gui, player, page + 1))
            slot == 47 -> gui.askText(
                player, "Block id", "",
                accept = { id -> gui.openLater(player, ModelScreen(gui, player, id.lowercase())) },
                cancel = { gui.openLater(player, ListScreen(gui, player, page)) },
            )
            slot == 49 -> player.closeInventory()
        }
    }
}

class DeleteScreen(gui: Gui, player: Player, private val id: String, private val page: Int) : Screen(gui, player, 3, "delete", "Delete $id?") {

    override fun render() {
        put(13, GuiAssets.decorate(gui.plugin.blocks.definitionOf(id)?.let { gui.plugin.items.create(it) } ?: ItemStack(Material.PAPER), id,
            listOf("Its settings file and its model file go to the plugin's deleted folder.", "The model stays when another block shows it.", "Blocks already placed in the world stay, and show nothing.")))
        button(11, "check", Material.LIME_DYE, "Yes, delete $id")
        button(15, "cross", Material.GRAY_DYE, "No, keep it")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when (slot) {
            11 -> {
                val problem = gui.editor.delete(id)
                if (problem != null) gui.say(player, problem, NamedTextColor.RED) else {
                    gui.discard(player, id)
                    gui.say(player, "Deleted '$id'. It is in the deleted folder if you need it back.")
                }
                gui.openLater(player, ListScreen(gui, player, page))
            }
            15 -> gui.openLater(player, ListScreen(gui, player, page))
        }
    }
}

class ModelScreen(gui: Gui, player: Player, private val id: String, private val page: Int = 0, private val change: Boolean = false) : Screen(gui, player, 6, "models", "Pick a model") {

    private val models = ModelLibrary.ids(gui.plugin.modelsFolder).sorted()

    override fun render() {
        for ((slot, model) in models.drop(page * PAGE).take(PAGE).withIndex()) {
            put(slot, GuiAssets.decorate(ItemStack(Material.PAPER), model, listOf(if (change) "Click to switch '$id' to this model" else "Click to make the block '$id' show this model")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        button(49, "back", Material.ARROW, "Back")
        if ((page + 1) * PAGE < models.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when {
            slot < PAGE && change -> models.getOrNull(page * PAGE + slot)?.let { model ->
                gui.draftOf(player, id)?.set("model", model)
                gui.openLater(player, OverviewScreen(gui, player, id))
            }
            slot < PAGE -> models.getOrNull(page * PAGE + slot)?.let { model ->
                val created = gui.editor.create(id, model)
                created.onSuccess { draft ->
                    gui.adopt(player, draft)
                    gui.openLater(player, OverviewScreen(gui, player, id))
                }.onFailure {
                    gui.say(player, it.message ?: "Could not make the block.", NamedTextColor.RED)
                    gui.openLater(player, ListScreen(gui, player))
                }
            }
            slot == 45 && page > 0 -> gui.openLater(player, ModelScreen(gui, player, id, page - 1, change))
            slot == 53 && (page + 1) * PAGE < models.size -> gui.openLater(player, ModelScreen(gui, player, id, page + 1, change))
            slot == 49 -> gui.openLater(player, if (change) OverviewScreen(gui, player, id) else ListScreen(gui, player))
        }
    }
}

class OverviewScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "overview", "Block: $id") {

    private val draft: Draft = gui.draftOf(player, id) ?: throw IllegalStateException("There is no block '$id'.")

    override fun render() {
        val item = gui.plugin.blocks.definitionOf(id)?.let { gui.plugin.items.create(it) } ?: ItemStack(Material.PAPER)
        put(4, GuiAssets.decorate(item, id, listOf("Model: ${draft.string("model", id)}", "Click to change the model")))
        val proxy = draft.string("proxy.block", "BARRIER")
        put(10, GuiAssets.decorate(ItemStack(Material.matchMaterial(proxy) ?: Material.BARRIER), "Proxy block: $proxy", listOf("The hidden block under the model")))
        button(11, "breaking", Material.IRON_PICKAXE, "Breaking", "Time, tool and tier")
        button(12, "drops", Material.CHEST, "Drops", "What breaking drops")
        button(13, "rules", Material.TNT, "Explosions and pistons")
        button(14, "display", Material.ITEM_FRAME, "Display", "Offset, scale, light")
        val registered = draft.string("item.registered", "")
        button(15, "item", Material.NAME_TAG, "Item", "The item that places it", if (registered.isEmpty()) "Right click: use a registered item" else "Uses the registered item '$registered'", "Right click to pick or remove it")
        button(16, "recipe", Material.CRAFTING_TABLE, "Recipe", "How to craft it")
        button(18, "back", Material.ARROW, "Blocks")
        button(22, "save", Material.LIME_DYE, "Save", "Writes blocks/$id.yml and loads the block")
        button(26, "reset", Material.RED_DYE, "Throw away changes", "Reads the block's file again")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        val next: Screen? = when (slot) {
            4 -> ModelScreen(gui, player, id, change = true)
            10 -> ProxyScreen(gui, player, id)
            11 -> BreakingScreen(gui, player, id)
            12 -> DropsScreen(gui, player, id)
            13 -> RulesScreen(gui, player, id)
            14 -> DisplayScreen(gui, player, id)
            15 -> if (event.isRightClick) ItemPickScreen(gui, player, id) else ItemScreen(gui, player, id)
            16 -> RecipeScreen(gui, player, id)
            18 -> ListScreen(gui, player)
            26 -> {
                gui.discard(player, id)
                OverviewScreen(gui, player, id)
            }
            else -> null
        }
        if (next != null) gui.openLater(player, next)
        if (slot == 22) {
            val problem = draft.save()
            if (problem == null) gui.say(player, "Saved blocks/$id.yml.") else gui.say(player, problem, NamedTextColor.RED)
        }
    }
}

private val PROXIES = listOf("BARRIER", "LIGHT", "STRUCTURE_VOID", "SMITHING_TABLE", "GLASS", "STONE", "OAK_PLANKS", "IRON_BLOCK")

class ProxyScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "proxy", "Proxy block: $id") {

    private val draft = gui.draftOf(player, id)!!

    override fun render() {
        val block = draft.string("proxy.block", "BARRIER")
        put(11, GuiAssets.decorate(ItemStack(Material.matchMaterial(block) ?: Material.BARRIER), "Proxy block: $block",
            listOf("What players click and collide with", "Click: next, right click: previous", "Or click a block in your inventory")))
        val mode = draft.string("proxy.mode", "custom")
        button(13, "mode", Material.CLOCK, "Mode: $mode", "custom: the breaking time below applies", "vanilla: the proxy block's own speed", "Click to switch")
        toggle(15, "Vanilla interaction", draft.bool("proxy.vanilla-interaction", false), "on: right click keeps what the proxy block does")
        back(18) { OverviewScreen(gui, player, id) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        when (slot) {
            11 -> draft.set("proxy.block", cycle(PROXIES, draft.string("proxy.block", "BARRIER").uppercase(), direction(event)))
            13 -> draft.set("proxy.mode", cycle(listOf("custom", "vanilla"), draft.string("proxy.mode", "custom"), 1))
            15 -> draft.set("proxy.vanilla-interaction", !draft.bool("proxy.vanilla-interaction", false))
            else -> return
        }
        redraw()
    }

    override fun clickBottom(event: InventoryClickEvent) {
        val type = event.currentItem?.type ?: return
        if (!type.isBlock) return
        draft.set("proxy.block", type.name)
        redraw()
    }
}

class BreakingScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "breaking", "Breaking: $id") {

    private val draft = gui.draftOf(player, id)!!
    private val tools = ToolType.entries.map { it.name.lowercase() }
    private val tiers = listOf("none") + ToolTier.entries.map { it.name.lowercase() }

    override fun render() {
        button(10, "time", Material.CLOCK, "Time: ${draft.double("breaking.time", 1.0)} s", "Click: +0.1, right click: -0.1", "Shift: 1 second")
        button(11, "tool", Material.IRON_PICKAXE, "Tool: ${draft.string("breaking.tool", "any")}", "Click: next, right click: previous")
        button(12, "tier", Material.NETHERITE_INGOT, "Tier: ${draft.string("breaking.tier", "none")}", "The weakest tool that gets drops", "Click: next, right click: previous")
        toggle(13, "Drops need the tool", draft.bool("breaking.drops-need-tool", false))
        button(14, "xp", Material.EXPERIENCE_BOTTLE, "Experience: ${draft.int("breaking.xp", 0)}", "Click: +1, right click: -1", "Shift: 10")
        back(18) { OverviewScreen(gui, player, id) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val steps = direction(event)
        when (slot) {
            10 -> draft.set("breaking.time", round(maxOf(0.1, draft.double("breaking.time", 1.0) + steps * if (isShift(event)) 1.0 else 0.1), 2))
            11 -> draft.set("breaking.tool", cycle(tools, draft.string("breaking.tool", "any"), steps))
            12 -> cycle(tiers, draft.string("breaking.tier", "none"), steps).let { draft.set("breaking.tier", if (it == "none") null else it) }
            13 -> draft.set("breaking.drops-need-tool", !draft.bool("breaking.drops-need-tool", false))
            14 -> draft.set("breaking.xp", maxOf(0, draft.int("breaking.xp", 0) + steps * if (isShift(event)) 10 else 1))
            else -> return
        }
        redraw()
    }
}

class DropsScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 6, "drops", "Drops: $id") {

    private val draft = gui.draftOf(player, id)!!

    private fun drops(): MutableList<MutableMap<String, Any?>> {
        if (!draft.has("drops")) return mutableListOf(linkedMapOf("block" to "self"))
        return draft.maps("drops").map { entry -> entry.entries.associate { it.key.toString() to it.value }.toMutableMap() }.toMutableList()
    }

    private fun save(list: List<Map<String, Any?>>) {
        draft.set("drops", list)
        redraw()
    }

    override fun render() {
        for ((slot, drop) in drops().withIndex().take(27)) {
            val self = drop["block"]?.toString().equals("self", true)
            val icon = if (self) gui.plugin.blocks.definitionOf(id)?.let { gui.plugin.items.create(it) } ?: ItemStack(Material.PAPER)
            else ItemStack(Material.matchMaterial(drop["item"]?.toString().orEmpty()) ?: Material.BARRIER)
            put(slot, GuiAssets.decorate(icon, if (self) "The block itself" else drop["item"].toString(), listOf(
                "Amount: ${drop["amount"] ?: 1}   Chance: ${(((drop["chance"] as? Number)?.toDouble() ?: 1.0) * 100).roundToInt()}%",
                "Click: chance +5%, right click: -5%",
                "Shift click: amount +1, shift right click: -1",
                "Q: remove",
            )))
        }
        button(45, "back", Material.ARROW, "Back")
        button(47, "block", Material.CHEST, "Drop the block itself", "Adds the block's own item")
        button(49, "reset", Material.RED_DYE, "Back to the default", "Drops only the block itself")
        button(51, "add", Material.HOPPER, "Add a drop", "Click an item in your inventory")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when {
            slot == 45 -> gui.openLater(player, OverviewScreen(gui, player, id))
            slot == 47 -> drops().also { if (it.none { d -> d["block"] != null }) it += linkedMapOf<String, Any?>("block" to "self") }.let(::save)
            slot == 49 -> {
                draft.set("drops", null)
                redraw()
            }
            slot < 27 -> {
                val list = drops()
                val drop = list.getOrNull(slot) ?: return
                when {
                    event.click == ClickType.DROP || event.click == ClickType.CONTROL_DROP -> list.removeAt(slot)
                    isShift(event) -> drop["amount"] = adjust(drop["amount"], direction(event))
                    else -> drop["chance"] = round(((drop["chance"] as? Number)?.toDouble() ?: 1.0) + direction(event) * 0.05).coerceIn(0.0, 1.0)
                }
                save(list)
            }
        }
    }

    override fun clickBottom(event: InventoryClickEvent) {
        val type = event.currentItem?.type ?: return
        if (!type.isItem) return
        val list = drops()
        if (list.size >= 27) return
        list += linkedMapOf<String, Any?>("item" to type.name, "amount" to 1, "chance" to 1.0)
        save(list)
    }

    private fun adjust(current: Any?, step: Int): Any {
        val range = AmountRange.parse(current?.toString() ?: "1") ?: AmountRange(1, 1)
        val max = maxOf(1, range.max + step)
        val min = minOf(range.min, max)
        return if (min == max) min else "$min-$max"
    }
}

class RulesScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "rules", "Rules: $id") {

    private val draft = gui.draftOf(player, id)!!

    override fun render() {
        toggle(11, "Explosion resistant", draft.bool("explosion.resistant", false), "on: explosions leave the block standing")
        toggle(12, "Explosion drops", draft.bool("explosion.drops", false), "When it is not resistant: drops its items")
        button(14, "piston", Material.PISTON, "Piston: ${draft.string("piston", "block")}", "block: will not move", "destroy: breaks with its drops", "Click to switch")
        back(18) { OverviewScreen(gui, player, id) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        when (slot) {
            11 -> draft.set("explosion.resistant", !draft.bool("explosion.resistant", false))
            12 -> draft.set("explosion.drops", !draft.bool("explosion.drops", false))
            14 -> draft.set("piston", cycle(listOf("block", "destroy"), draft.string("piston", "block"), 1))
            else -> return
        }
        redraw()
    }
}

class DisplayScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 6, "display", "Display: $id") {

    private val draft = gui.draftOf(player, id)!!
    private val contexts = DisplayContext.entries.map { it.name.lowercase() }

    private fun triple(path: String, default: Double): MutableList<Double> {
        val raw = draft.get(path)
        val list = when (raw) {
            is Number -> List(3) { raw.toDouble() }
            is List<*> -> raw.map { (it as? Number)?.toDouble() ?: default }
            else -> emptyList()
        }
        return MutableList(3) { list.getOrElse(it) { default } }
    }

    override fun render() {
        val offset = triple("display.offset", 0.0)
        val scale = triple("display.scale", 1.0)
        for (axis in 0..2) {
            val name = "xyz"[axis]
            button(10 + axis, "offset", Material.COMPASS, "Offset $name: ${round(offset[axis])}", "Blocks from the centre", "Click: +0.01, right click: -0.01", "Shift: 0.1")
            button(19 + axis, "scale", Material.SPYGLASS, "Scale $name: ${round(scale[axis])}", "Click: +0.01, right click: -0.01", "Shift: 0.1")
        }
        button(14, "view", Material.ENDER_EYE, "View range: ${round(draft.double("display.view-range", 1.0))}", "Click: +0.1, right click: -0.1", "Shift: 1")
        val brightness = if (draft.has("display.brightness")) draft.int("display.brightness", 15).toString() else "like the world"
        button(15, "light", Material.LIGHT, "Light: $brightness", "Click: +1, right click: -1", "Below 0: like the world")
        toggle(23, "Rotate to placer", draft.bool("display.rotate-to-placer", false), "Faces the player who places it")
        button(24, "context", Material.ITEM_FRAME, "Context: ${draft.string("display.context", "none")}", "Which Display tab setting the placed block uses", "Click: next, right click: previous")
        back(45) { OverviewScreen(gui, player, id) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val steps = direction(event) * if (isShift(event)) 10 else 1
        when (slot) {
            in 10..12 -> triple("display.offset", 0.0).let { it[slot - 10] = round((it[slot - 10] + steps * 0.01).coerceIn(-16.0, 16.0)); draft.set("display.offset", it) }
            in 19..21 -> triple("display.scale", 1.0).let { it[slot - 19] = round(maxOf(0.01, it[slot - 19] + steps * 0.01)); draft.set("display.scale", it) }
            14 -> draft.set("display.view-range", round((draft.double("display.view-range", 1.0) + steps * 0.1).coerceIn(0.1, 100.0)))
            15 -> {
                val now = if (draft.has("display.brightness")) draft.int("display.brightness", 15) else -1
                val next = (now + direction(event)).coerceIn(-1, 15)
                draft.set("display.brightness", if (next < 0) null else next)
            }
            23 -> draft.set("display.rotate-to-placer", !draft.bool("display.rotate-to-placer", false))
            24 -> draft.set("display.context", cycle(contexts, draft.string("display.context", "none"), direction(event)))
            else -> return
        }
        redraw()
    }
}

class ItemScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "item", "Item: $id") {

    override fun takesCursor(event: InventoryClickEvent) = event.click == ClickType.RIGHT || !event.cursor.isEmpty

    private val draft = gui.draftOf(player, id)!!

    override fun render() {
        val saved = draft.item()
        val base = Material.matchMaterial(draft.string("item.base", "PAPER")) ?: Material.PAPER
        put(10, GuiAssets.decorate(saved?.asOne() ?: ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE), if (saved != null) "The saved item" else "No exact item saved",
            listOf("Shift click an item in your inventory to save it exactly,", "or right click one to pick it up, then click here.", "Click here with an empty cursor to clear it.")))
        toggle(11, "Use the block's model", draft.overrideModel(), "on: the item shows the block's model", "off: it keeps the model the saved item has")
        val decides = if (saved != null) "The saved item decides this" else "Click an item in your inventory to change it (left click)"
        button(12, "model", base, "Base item: ${draft.string("item.base", "PAPER")}", decides)
        toggle(13, "Shine", draft.bool("item.glint", false), if (saved != null) "The saved item decides this" else "Looks enchanted")
        button(14, "name", Material.NAME_TAG, "Name: ${draft.string("item.name", "(from the id)")}", "Click to change, right click to clear", "Colors: &a, &b, &l...")
        val lore = draft.strings("item.lore")
        button(15, "lore", Material.WRITABLE_BOOK, "Lore: ${lore.size} line(s)", *(lore.map { "  $it" } + listOf("Click to add a line", "Right click to remove the last")).toTypedArray())
        back(18) { OverviewScreen(gui, player, id) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val saved = draft.item() != null
        when (slot) {
            10 -> {
                val cursor = event.cursor
                draft.setItem(if (cursor.isEmpty) null else cursor, draft.overrideModel())
            }
            11 -> draft.setItem(draft.item(), !draft.overrideModel())
            13 -> if (!saved) draft.set("item.glint", !draft.bool("item.glint", false))
            14 -> {
                if (event.isRightClick) draft.set("item.name", null) else {
                    gui.askText(player, "Item name", draft.string("item.name", ""), accept = {
                        draft.set("item.name", it)
                        gui.openLater(player, ItemScreen(gui, player, id))
                    }, cancel = { gui.openLater(player, ItemScreen(gui, player, id)) })
                    return
                }
            }
            15 -> {
                val lore = draft.strings("item.lore")
                if (event.isRightClick) draft.set("item.lore", lore.dropLast(1)) else {
                    gui.askText(player, "Lore line", "", accept = {
                        draft.set("item.lore", lore + it)
                        gui.openLater(player, ItemScreen(gui, player, id))
                    }, cancel = { gui.openLater(player, ItemScreen(gui, player, id)) })
                    return
                }
            }
            else -> return
        }
        redraw()
    }

    override fun clickBottom(event: InventoryClickEvent) {
        val clicked = event.currentItem?.takeUnless { it.isEmpty } ?: return
        if (isShift(event)) draft.setItem(clicked, draft.overrideModel())
        else if (draft.item() == null && clicked.type.isItem) draft.set("item.base", clicked.type.name)
        redraw()
    }
}

class RecipeScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "recipe", "Recipe: $id") {

    override fun takesCursor(event: InventoryClickEvent) = true

    private val draft = gui.draftOf(player, id)!!
    private val grid: MutableList<ItemStack?> = draft.recipeGrid().toMutableList()
    private var exactAll = false
    private var amount = draft.int("recipe.amount", 1)
    private val categories = listOf("none", "building", "redstone", "equipment", "misc")

    override fun render() {
        for ((index, slot) in GRID.withIndex()) put(slot, grid[index]?.let { GuiAssets.decorate(it.asOne(), it.type.name.lowercase().replace('_', ' '), listOf("Click with an empty cursor to clear")) })
        val result = gui.plugin.blocks.definitionOf(id)?.let { gui.plugin.items.create(it, amount) } ?: ItemStack(Material.PAPER, amount.coerceAtLeast(1))
        put(16, GuiAssets.decorate(result, "Result: $amount", listOf("Click: +1, right click: -1")))
        toggle(10, "Exact items", exactAll, "on: every ingredient must be that exact item", "off: plain items match by material,", "custom items are always exact")
        button(8, "category", Material.KNOWLEDGE_BOOK, "Category: ${draft.string("recipe.category", "none")}", "Where it shows in the recipe book", "Click to change")
        button(25, "reset", Material.RED_DYE, "Clear the grid")
        back(18) { OverviewScreen(gui, player, id) }
    }

    private fun sync() {
        val category = draft.string("recipe.category", "none").takeUnless { it == "none" }
        draft.setRecipe(grid, exactAll, amount, draft.get("recipe.group") as? String, category)
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val index = GRID.indexOf(slot)
        when {
            index >= 0 -> grid[index] = event.cursor.takeUnless { it.isEmpty }?.asOne()
            slot == 16 -> amount = (amount + direction(event)).coerceIn(1, 64)
            slot == 10 -> exactAll = !exactAll
            slot == 8 -> {
                val next = cycle(categories, draft.string("recipe.category", "none"), 1)
                draft.set("recipe.category", if (next == "none") null else next)
            }
            slot == 25 -> grid.replaceAll { null }
            else -> return
        }
        sync()
        redraw()
    }

    override fun clickBottom(event: InventoryClickEvent) {
        val clicked = event.currentItem?.takeUnless { it.isEmpty } ?: return
        val free = grid.indexOfFirst { it == null }
        if (free < 0) return
        grid[free] = clicked.asOne()
        sync()
        redraw()
    }

    private companion object {
        val GRID = listOf(3, 4, 5, 12, 13, 14, 21, 22, 23)
    }
}
