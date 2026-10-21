package io.github.ratr2.repack.gui

import io.github.ratr2.repack.edit.EntityDraft
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import kotlin.math.round

private const val ENTITY_PAGE = 45

private fun tenth(value: Double) = round(value * 10) / 10

/** Every model entity type, a page at a time. Click one to edit it. */
class EntityListScreen(gui: Gui, player: Player, private val page: Int = 0) : Screen(gui, player, 6, "list", "Repack entities") {

    private val ids = gui.plugin.modelEntities.definitions.keys.sorted()

    override fun render() {
        for ((slot, id) in ids.drop(page * ENTITY_PAGE).take(ENTITY_PAGE).withIndex()) {
            val model = gui.plugin.modelEntities.definitions.getValue(id).model
            put(slot, GuiAssets.decorate(ItemStack(Material.ARMOR_STAND), id, listOf("Model: $model", "Click to edit", "Shift right click to delete")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        button(47, "new", Material.EMERALD, "New entity", "Pick an id, then a model")
        back(49) { HubScreen(gui, player) }
        if ((page + 1) * ENTITY_PAGE < ids.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        when {
            slot < ENTITY_PAGE -> ids.getOrNull(page * ENTITY_PAGE + slot)?.let {
                gui.openLater(player, if (event.click == ClickType.SHIFT_RIGHT) EntityDeleteScreen(gui, player, it, page) else EntityOverviewScreen(gui, player, it))
            }
            slot == 45 && page > 0 -> gui.openLater(player, EntityListScreen(gui, player, page - 1))
            slot == 53 && (page + 1) * ENTITY_PAGE < ids.size -> gui.openLater(player, EntityListScreen(gui, player, page + 1))
            slot == 47 -> gui.askText(
                player, "Entity id", "",
                accept = { text -> gui.openLater(player, EntityModelScreen(gui, player, text.lowercase(), change = false)) },
                cancel = { gui.openLater(player, EntityListScreen(gui, player, page)) },
            )
        }
    }
}

/** Asks before an entity type's file is taken away. */
class EntityDeleteScreen(gui: Gui, player: Player, private val id: String, private val page: Int) : Screen(gui, player, 3, "confirm", "Delete $id?") {

    override fun render() {
        put(13, GuiAssets.decorate(ItemStack(Material.ARMOR_STAND), id, listOf("Its settings file goes to the plugin's deleted folder.", "Entities already in the world stay saved, and show nothing.")))
        button(11, "check", Material.LIME_DYE, "Yes, delete $id")
        button(15, "cross", Material.GRAY_DYE, "No, keep it")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when (slot) {
            11 -> {
                val problem = gui.entityEditor.delete(id)
                if (problem != null) gui.say(player, problem, NamedTextColor.RED) else {
                    gui.discardEntity(player, id)
                    gui.say(player, "Deleted entity '$id'. It is in the deleted folder if you need it back.")
                }
                gui.openLater(player, EntityListScreen(gui, player, page))
            }
            15 -> gui.openLater(player, EntityListScreen(gui, player, page))
        }
    }
}

/** Pick the model of a new entity type, or the model an existing one switches to. */
class EntityModelScreen(gui: Gui, player: Player, private val id: String, private val change: Boolean, private val page: Int = 0) : Screen(gui, player, 6, "models", "Pick a model") {

    private val models = io.github.ratr2.repack.model.ModelLibrary.ids(gui.plugin.modelsFolder).sorted()

    override fun render() {
        for ((slot, model) in models.drop(page * ENTITY_PAGE).take(ENTITY_PAGE).withIndex()) {
            put(slot, GuiAssets.decorate(ItemStack(Material.PAPER), model, listOf("Click to make the entity '$id' show this model")))
        }
        if (page > 0) button(45, "previous", Material.ARROW, "Previous page")
        back(49) { if (change) EntityOverviewScreen(gui, player, id) else EntityListScreen(gui, player) }
        if ((page + 1) * ENTITY_PAGE < models.size) button(53, "next", Material.ARROW, "Next page")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        when {
            slot < ENTITY_PAGE -> models.getOrNull(page * ENTITY_PAGE + slot)?.let { model ->
                if (change) {
                    gui.entityDraftOf(player, id)?.set("model", model)?.set("animation", null)
                    gui.openLater(player, EntityOverviewScreen(gui, player, id))
                } else {
                    gui.entityEditor.create(id, model).onSuccess { draft ->
                        gui.adoptEntity(player, draft)
                        gui.openLater(player, EntityOverviewScreen(gui, player, id))
                    }.onFailure {
                        gui.say(player, it.message ?: "Could not make the entity.", NamedTextColor.RED)
                        gui.openLater(player, EntityListScreen(gui, player))
                    }
                }
            }
            slot == 45 && page > 0 -> gui.openLater(player, EntityModelScreen(gui, player, id, change, page - 1))
            slot == 53 && (page + 1) * ENTITY_PAGE < models.size -> gui.openLater(player, EntityModelScreen(gui, player, id, change, page + 1))
        }
    }
}

/** One model entity type: its model, animation, size, offset, light and view range, with save and a button that puts one at your feet. */
class EntityOverviewScreen(gui: Gui, player: Player, private val id: String) : Screen(gui, player, 3, "overview", "Entity: $id") {

    private val draft: EntityDraft = gui.entityDraftOf(player, id) ?: throw IllegalStateException("There is no entity '$id'.")

    private fun model() = draft.string("model", "")

    private fun clips(): List<String> = gui.plugin.modelClips[model()].orEmpty()

    private fun scale(): Double = draft.doubles("scale").firstOrNull() ?: draft.double("scale", 1.0)

    private fun offsetY(): Double = draft.doubles("offset").getOrNull(1) ?: 0.0

    override fun render() {
        put(4, GuiAssets.decorate(ItemStack(Material.ARMOR_STAND), id, listOf("Model: ${model()}")))
        button(10, "models", Material.PAINTING, "Model: ${model()}", "Click to pick another .bbmodel")
        val animation = draft.string("animation", "")
        button(11, "animation", Material.CLOCK, "Animation: ${animation.ifEmpty { "none" }}", if (clips().isEmpty()) "This model has no animations (or the pack has not been built yet)" else "Click: next, right click: previous, shift click: none")
        button(12, "scale", Material.SPYGLASS, "Scale: ${tenth(scale())}", "Click: +0.1, right click: -0.1", "Shift: 1")
        button(13, "offset", Material.COMPASS, "Offset up: ${tenth(offsetY())}", "Blocks the model sits above its position", "Click: +0.1, right click: -0.1", "Shift: 1")
        val brightness = if (draft.has("brightness")) draft.int("brightness", -1) else -1
        button(14, "light", Material.LIGHT, "Light: ${if (brightness < 0) "like the world" else brightness}", "Click: +1, right click: -1", "Below 0: like the world")
        button(15, "view", Material.ENDER_EYE, "View range: ${tenth(draft.double("view-range", 1.0))}", "1.0 is 64 blocks", "Click: +0.1, right click: -0.1", "Shift: 1")
        button(16, "summon", Material.EGG, "Put one here", "Saves, then puts one of these where you stand")
        back(18) { EntityListScreen(gui, player) }
        button(22, "save", Material.LIME_DYE, "Save", "Writes entities/$id.yml and loads it")
        button(26, "reset", Material.RED_DYE, "Throw away changes", "Reads the entity's file again")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val step = if (isShift(event)) 1.0 else 0.1
        when (slot) {
            10 -> {
                gui.openLater(player, EntityModelScreen(gui, player, id, change = true))
                return
            }
            11 -> {
                val options = listOf("") + clips()
                val at = options.indexOf(draft.string("animation", "")).coerceAtLeast(0)
                val next = if (isShift(event)) 0 else Math.floorMod(at + direction(event), options.size)
                draft.set("animation", options[next].ifEmpty { null })
            }
            12 -> draft.set("scale", tenth((scale() + direction(event) * step).coerceIn(0.1, 100.0)))
            13 -> draft.set("offset", listOf(draft.doubles("offset").getOrNull(0) ?: 0.0, tenth(offsetY() + direction(event) * step), draft.doubles("offset").getOrNull(2) ?: 0.0))
            14 -> {
                val now = if (draft.has("brightness")) draft.int("brightness", -1) else -1
                val next = (now + direction(event)).coerceIn(-1, 15)
                draft.set("brightness", if (next < 0) null else next)
            }
            15 -> draft.set("view-range", tenth((draft.double("view-range", 1.0) + direction(event) * step).coerceIn(0.1, 100.0)))
            16 -> {
                val problem = draft.save()
                if (problem != null) {
                    gui.say(player, problem, NamedTextColor.RED)
                    return
                }
                val definition = gui.plugin.modelEntities.definitions[id]
                if (definition == null) gui.say(player, "The entity could not be loaded.", NamedTextColor.RED) else {
                    val at: Location = player.location
                    gui.plugin.modelEntities.spawn(definition, at)
                    gui.say(player, "Put a '$id' at ${at.blockX} ${at.blockY} ${at.blockZ}. /repack entity remove takes it away.")
                }
                return
            }
            22 -> {
                val problem = draft.save()
                if (problem != null) gui.say(player, problem, NamedTextColor.RED) else gui.say(player, "Saved entities/$id.yml and loaded it.")
                return
            }
            26 -> {
                gui.discardEntity(player, id)
                gui.openLater(player, EntityOverviewScreen(gui, player, id))
                return
            }
            else -> return
        }
        redraw()
    }
}
