package io.github.ratr2.repack.gui

import io.github.ratr2.repack.edit.ItemDraft
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack

private const val ITEM_PAGE = 45

class ItemListScreen(gui: Gui, player: Player, private val page: Int = 0) : Screen(gui, player, 6, "list", "Repack items") {

    private val ids = gui.plugin.customItems.ids.sorted()

    override fun render() {
        for ((slot, id) in ids.drop(page * ITEM_PAGE).take(ITEM_PAGE).withIndex()) {
            val item = gui.plugin.customItems.definitionOf(id)?.let { gui.plugin.customItems.create(it) } ?: ItemStack(Material.PAPER)
            put(slot, GuiAssets.decorate(item, id, listOf("Click to edit", "Shift right click to delete")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        button(47, "new", Material.EMERALD, "New item", "Pick an id")
        back(49) { HubScreen(gui, player) }
        if ((page + 1) * ITEM_PAGE < ids.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        when {
            slot < ITEM_PAGE -> ids.getOrNull(page * ITEM_PAGE + slot)?.let {
                gui.openLater(player, if (event.click == ClickType.SHIFT_RIGHT) ItemDeleteScreen(gui, player, it, page) else ItemOverviewScreen(gui, player, it))
            }
            slot == 45 && page > 0 -> gui.openLater(player, ItemListScreen(gui, player, page - 1))
            slot == 53 && (page + 1) * ITEM_PAGE < ids.size -> gui.openLater(player, ItemListScreen(gui, player, page + 1))
            slot == 47 -> gui.askText(
                player, "Item id", "",
                accept = { text ->
                    val id = text.lowercase()
                    gui.itemEditor.create(id).onSuccess { draft ->
                        gui.adoptItem(player, draft)
                        gui.openLater(player, ItemOverviewScreen(gui, player, id))
                    }.onFailure {
                        gui.say(player, it.message ?: "Could not make the item.", NamedTextColor.RED)
                        gui.openLater(player, ItemListScreen(gui, player, page))
                    }
                },
                cancel = { gui.openLater(player, ItemListScreen(gui, player, page)) },
            )
        }
    }
}

class ItemDeleteScreen(gui: Gui, player: Player, private val id: String, private val page: Int) : Screen(gui, player, 3, "confirm", "Delete $id?") {

    override fun render() {
        put(13, GuiAssets.decorate(gui.plugin.customItems.definitionOf(id)?.let { gui.plugin.customItems.create(it) } ?: ItemStack(Material.PAPER), id,
            listOf("Its settings file goes to the plugin's deleted folder.", "Items already given keep working.", "Blocks that use it make their own item.")))
        button(11, "check", Material.LIME_DYE, "Yes, delete $id")
        button(15, "cross", Material.GRAY_DYE, "No, keep it")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when (slot) {
            11 -> {
                val problem = gui.itemEditor.delete(id)
                if (problem != null) gui.say(player, problem, NamedTextColor.RED) else {
                    gui.discardItem(player, id)
                    gui.say(player, "Deleted item '$id'. It is in the deleted folder if you need it back.")
                }
                gui.openLater(player, ItemListScreen(gui, player, page))
            }
            15 -> gui.openLater(player, ItemListScreen(gui, player, page))
        }
    }
}

class ItemOverviewScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "overview", "Item: $id") {

    private val draft: ItemDraft = gui.itemDraftOf(player, id) ?: throw IllegalStateException("There is no item '$id'.")

    override fun takesCursor(event: InventoryClickEvent) = event.click == ClickType.RIGHT || !event.cursor.isEmpty

    override fun render() {
        val saved = draft.stack()
        val base = Material.matchMaterial(draft.string("base", "PAPER")) ?: Material.PAPER
        val preview = draft.definition()?.let { runCatching { gui.plugin.customItems.create(it) }.getOrNull() } ?: ItemStack(base)
        put(4, GuiAssets.decorate(preview, id, listOf("Model: ${draft.string("model", "").ifEmpty { "none" }}")))
        button(10, "base", base, "Base item: ${base.name}", if (saved != null) "The saved item decides this" else "Click an item in your inventory to change it (left click)")
        button(11, "name", Material.NAME_TAG, "Name: ${draft.string("name", "(from the id)")}", "Click to change, right click to clear", "Colors: &a, <#ff8800>...")
        val lore = draft.strings("lore")
        button(12, "lore", Material.WRITABLE_BOOK, "Lore: ${lore.size} line(s)", *(lore.map { "  $it" } + listOf("Click to add a line", "Right click to remove the last")).toTypedArray())
        toggle(13, "Shine", draft.bool("glint", false), if (saved != null) "The saved item decides this" else "Looks enchanted")
        button(14, "models", Material.PAINTING, "Model: ${draft.string("model", "").ifEmpty { "none" }}", "Click to pick a .bbmodel", "Right click to remove it")
        put(15, GuiAssets.decorate(saved?.asOne() ?: ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE), if (saved != null) "The saved item" else "No exact item saved",
            listOf("Shift click an item in your inventory to save it exactly,", "or right click one to pick it up, then click here.", "Click here with an empty cursor to clear it.")))
        val components = draft.string("components", "")
        button(16, "components", Material.COMMAND_BLOCK, "Components: ${components.ifEmpty { "none" }}", "For developers, as in /give: [minecraft:custom_data={a:1}]", "Click to change, right click to clear")
        back(18) { ItemListScreen(gui, player) }
        button(22, "save", Material.LIME_DYE, "Save", "Writes items/$id.yml and loads the item")
        button(26, "reset", Material.RED_DYE, "Throw away changes", "Reads the item's file again")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val saved = draft.stack() != null
        when (slot) {
            11 -> {
                if (event.isRightClick) draft.set("name", null) else {
                    askThenReturn("Item name", draft.string("name", "")) { draft.set("name", it) }
                    return
                }
            }
            12 -> {
                val lore = draft.strings("lore")
                if (event.isRightClick) draft.set("lore", lore.dropLast(1)) else {
                    askThenReturn("Lore line", "") { draft.set("lore", lore + it) }
                    return
                }
            }
            13 -> if (!saved) draft.set("glint", !draft.bool("glint", false))
            14 -> {
                if (event.isRightClick) draft.set("model", null) else {
                    gui.openLater(player, ItemModelScreen(gui, player, id))
                    return
                }
            }
            15 -> draft.setStack(event.cursor.takeUnless { it.isEmpty }, draft.overrideModel())
            16 -> {
                if (event.isRightClick) draft.set("components", null) else {
                    askThenReturn("Components", draft.string("components", "")) { draft.set("components", it.ifBlank { null }) }
                    return
                }
            }
            22 -> {
                val problem = draft.save()
                if (problem != null) gui.say(player, problem, NamedTextColor.RED) else gui.say(player, "Saved items/$id.yml and loaded the item.")
                return
            }
            26 -> {
                gui.discardItem(player, id)
                gui.openLater(player, ItemOverviewScreen(gui, player, id))
                return
            }
            else -> return
        }
        redraw()
    }

    private fun askThenReturn(title: String, initial: String, apply: (String) -> Unit) {
        gui.askText(player, title, initial, accept = {
            apply(it)
            gui.openLater(player, ItemOverviewScreen(gui, player, id))
        }, cancel = { gui.openLater(player, ItemOverviewScreen(gui, player, id)) })
    }

    override fun clickBottom(event: InventoryClickEvent) {
        val clicked = event.currentItem?.takeUnless { it.isEmpty } ?: return
        if (isShift(event)) draft.setStack(clicked, draft.overrideModel())
        else if (draft.stack() == null && clicked.type.isItem) draft.set("base", clicked.type.name)
        redraw()
    }
}

class ItemModelScreen(gui: Gui, player: Player, private val id: String, private val page: Int = 0) : Screen(gui, player, 6, "models", "Pick a model") {

    private val models = io.github.ratr2.repack.model.ModelLibrary.ids(gui.plugin.modelsFolder).sorted()

    override fun render() {
        for ((slot, model) in models.drop(page * ITEM_PAGE).take(ITEM_PAGE).withIndex()) {
            put(slot, GuiAssets.decorate(ItemStack(Material.PAPER), model, listOf("Click to make the item '$id' show this model")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        back(49) { ItemOverviewScreen(gui, player, id) }
        if ((page + 1) * ITEM_PAGE < models.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        when {
            slot < ITEM_PAGE -> models.getOrNull(page * ITEM_PAGE + slot)?.let { model ->
                gui.itemDraftOf(player, id)?.set("model", model)
                gui.openLater(player, ItemOverviewScreen(gui, player, id))
            }
            slot == 45 && page > 0 -> gui.openLater(player, ItemModelScreen(gui, player, id, page - 1))
            slot == 53 && (page + 1) * ITEM_PAGE < models.size -> gui.openLater(player, ItemModelScreen(gui, player, id, page + 1))
        }
    }
}

class ItemPickScreen(gui: Gui, player: Player, private val blockId: String, private val page: Int = 0) : Screen(gui, player, 6, "list", "Item for $blockId") {

    private val ids = gui.plugin.customItems.ids.sorted()

    override fun render() {
        for ((slot, id) in ids.drop(page * ITEM_PAGE).take(ITEM_PAGE).withIndex()) {
            val item = gui.plugin.customItems.definitionOf(id)?.let { gui.plugin.customItems.create(it) } ?: ItemStack(Material.PAPER)
            put(slot, GuiAssets.decorate(item, id, listOf("Click to make '$blockId' use this item")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        button(47, "cross", Material.GRAY_DYE, "No registered item", "The block makes its own item from its item settings")
        back(49) { OverviewScreen(gui, player, blockId) }
        if ((page + 1) * ITEM_PAGE < ids.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val draft = gui.draftOf(player, blockId) ?: return
        when {
            slot < ITEM_PAGE -> ids.getOrNull(page * ITEM_PAGE + slot)?.let {
                draft.set("item.registered", it)
                gui.openLater(player, OverviewScreen(gui, player, blockId))
            }
            slot == 45 && page > 0 -> gui.openLater(player, ItemPickScreen(gui, player, blockId, page - 1))
            slot == 53 && (page + 1) * ITEM_PAGE < ids.size -> gui.openLater(player, ItemPickScreen(gui, player, blockId, page + 1))
            slot == 47 -> {
                draft.set("item.registered", null)
                gui.openLater(player, OverviewScreen(gui, player, blockId))
            }
        }
    }
}
