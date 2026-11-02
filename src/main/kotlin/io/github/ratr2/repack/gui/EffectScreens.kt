package io.github.ratr2.repack.gui

import io.github.ratr2.repack.RepackCommand
import io.github.ratr2.repack.pack.EffectEditor
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta

/** Builds the pack and sends it to one player only, then says whether their client loaded it. Nobody else gets anything until the edit is pushed. */
internal object EffectTest {

    fun run(gui: Gui, player: Player) {
        val plugin = gui.plugin
        if (!plugin.sending) {
            gui.say(player, "Repack does not send the pack (resource-pack.send: false), so there is nothing to test.", NamedTextColor.RED)
            return
        }
        gui.say(player, "Building the pack...", NamedTextColor.GRAY)
        plugin.rebuild { report ->
            if (report.rejected || report.entries.isEmpty()) {
                gui.say(player, "The build was rejected, so nothing was sent.", NamedTextColor.RED)
                report.errors.take(3).forEach { gui.say(player, it, NamedTextColor.RED) }
                return@rebuild
            }
            if (!player.isOnline) return@rebuild
            val sent = plugin.sender.sendBuilt(player) { loaded ->
                if (!player.isOnline) return@sendBuilt
                if (loaded) {
                    gui.say(player, "Your client loaded it. Texts in an effect's color now show the effect, and so do the previews in these screens. Nobody else has it.")
                } else {
                    gui.say(player, "Your client could not load it: a shader failed to compile or the pack was declined. Its log has the reason. Putting the published pack back.", NamedTextColor.RED)
                    plugin.sender.send(listOf(player))
                }
            }
            if (!sent) gui.say(player, "Your client version cannot take the pack (1.21.4 or newer), or it was not built.", NamedTextColor.RED)
        }
    }

    fun revert(gui: Gui, player: Player) {
        val sent = gui.plugin.sender.send(listOf(player))
        gui.say(player, if (sent > 0) "Sent you the published pack again." else "Nothing is published yet, so there is nothing to go back to.", if (sent > 0) NamedTextColor.GREEN else NamedTextColor.YELLOW)
    }

    fun push(gui: Gui, player: Player) {
        gui.say(player, "Building the pack and sending it to everyone...", NamedTextColor.GRAY)
        gui.plugin.rebuildAndPush { report ->
            if (report.rejected) {
                gui.say(player, "The build was rejected, so nothing was sent.", NamedTextColor.RED)
                report.errors.take(3).forEach { gui.say(player, it, NamedTextColor.RED) }
            } else {
                gui.say(player, if (report.unchanged) "It is the same as the pack last pushed, so nothing was sent." else "Sent it to ${report.sent ?: 0} player(s).")
            }
        }
    }
}

/** The list of text effects, and the way into their screens. */
class EffectsScreen(gui: Gui, player: Player) : Screen(gui, player, 5, "effects", "Text effects") {

    private val config get() = gui.plugin.config

    private fun names() = EffectEditor.names(config)

    override fun render() {
        for ((index, name) in names().take(EffectEditor.MAX_EFFECTS).withIndex()) {
            val color = EffectEditor.color(config, name, "trigger") ?: 0xFFFFFF
            put(SLOTS[index], preview(name, color, EffectEditor.type(config, name), "Trigger ${EffectEditor.hex(color)}", "Click to edit"))
        }
        toggle(38, "Text effects", EffectEditor.enabled(config), "Off: no text effect is drawn")
        button(40, "settings", Material.REPEATER, "Defaults", "The numbers an effect uses when it sets none")
        button(42, "new", Material.EMERALD, "New effect", "Starts from a free color")
        back(36) { HubScreen(gui, player) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        if (!player.hasPermission(RepackCommand.SETTINGS)) return
        when (slot) {
            38 -> {
                EffectEditor.setEnabled(config, !EffectEditor.enabled(config))
                gui.plugin.saveConfig()
                redraw()
            }
            40 -> gui.openLater(player, EffectScreen(gui, player, null))
            42 -> gui.askText(player, "Effect name", "", accept = { text ->
                val name = text.lowercase()
                val problem = EffectEditor.create(config, name)
                if (problem != null) {
                    gui.say(player, problem, NamedTextColor.RED)
                    gui.openLater(player, EffectsScreen(gui, player))
                } else {
                    gui.plugin.saveConfig()
                    EffectEditor.refresh(config)
                    gui.openLater(player, EffectScreen(gui, player, name))
                }
            }, cancel = { gui.openLater(player, EffectsScreen(gui, player)) })
            else -> names().getOrNull(SLOTS.indexOf(slot))?.let { gui.openLater(player, EffectScreen(gui, player, it)) }
        }
    }

    private companion object {
        val SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)
    }
}

/** One text effect, or (with a null name) the defaults. Its name is drawn in the trigger color, so it shows the effect once a test pack is loaded. */
class EffectScreen(gui: Gui, player: Player, private val name: String?) : Screen(gui, player, 5, "effect", name ?: "Effect defaults") {

    private val config get() = gui.plugin.config
    private var confirming: String? = null

    override fun render() {
        if (name != null) {
            val trigger = EffectEditor.color(config, name, "trigger") ?: 0xFFFFFF
            val from = EffectEditor.color(config, name, "from") ?: trigger
            val to = EffectEditor.color(config, name, "to") ?: trigger
            button(10, "mode", Material.COMPARATOR, "Type: ${EffectEditor.type(config, name)}", "Click for the next, right click for the last", "shine, pulse, wave, strobe, rainbow")
            button(11, "mode", Material.BLACK_CONCRETE, "Drop shadow: ${EffectEditor.shadow(config, name)}", "match follows the text color, legacy guesses from how dark it is", "Click to switch")
            put(12, dye("Trigger ${EffectEditor.hex(trigger)}", trigger, "The text color that starts the effect", "Click to change"))
            put(13, dye("From ${EffectEditor.hex(from)}", from, "The dark end of the gradient", "Not used by rainbow", "Click to change"))
            put(14, dye("To ${EffectEditor.hex(to)}", to, "The bright end of the gradient", "Not used by rainbow", "Click to change"))
            put(16, preview(name, trigger, EffectEditor.type(config, name), "Shows moving after Test on me", "Texts in ${EffectEditor.hex(trigger)} use it"))
        } else {
            button(13, "settings", Material.REPEATER, "Defaults", "Used by every effect that sets no value of its own")
        }
        for ((index, field) in EffectEditor.FIELDS.withIndex()) {
            val value = EffectEditor.format(EffectEditor.number(config, name, field))
            val own = name?.let { EffectEditor.overrides(config, it, field) }
            val where = when (own) {
                null -> ""
                true -> " (own)"
                false -> " (default)"
            }
            val lore = mutableListOf(field.note, "Click: +${EffectEditor.format(field.step)}, right click: -${EffectEditor.format(field.step)}", "Shift: ${EffectEditor.format(field.big)}")
            if (name != null) lore += "Q: use the default"
            button(NUMBER_SLOTS[index], "time", Material.CLOCK, "${field.label}: $value$where", *lore.toTypedArray())
        }
        button(28, "check", Material.LIME_DYE, "Test on me", "Builds the pack and sends it to you only", "Your client says whether it loaded")
        button(29, "reset", Material.ORANGE_DYE, "Back to published", "Sends you the pack everyone has")
        button(30, "save", Material.GREEN_CONCRETE, if (confirming == "push") "Click again to send" else "Push to everyone", "Builds and sends the pack to all players online")
        if (name != null) button(34, "cross", Material.RED_CONCRETE, if (confirming == "delete") "Click again to delete" else "Delete", "Removes the effect from config.yml")
        back(36) { EffectsScreen(gui, player) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        if (!player.hasPermission(RepackCommand.SETTINGS)) return
        val was = confirming
        confirming = null
        when {
            slot in NUMBER_SLOTS -> number(EffectEditor.FIELDS[NUMBER_SLOTS.indexOf(slot)], event)
            name != null && slot == 10 -> {
                EffectEditor.cycleType(config, name, event.isRightClick)
                changed()
            }
            name != null && slot == 11 -> {
                EffectEditor.cycleShadow(config, name)
                changed()
            }
            name != null && slot in COLOR_SLOTS -> color(COLOR_SLOTS.getValue(slot))
            slot == 28 -> EffectTest.run(gui, player)
            slot == 29 -> EffectTest.revert(gui, player)
            slot == 30 -> if (was == "push") EffectTest.push(gui, player) else {
                confirming = "push"
                redraw()
            }
            name != null && slot == 34 -> if (was == "delete") {
                EffectEditor.delete(config, name)
                gui.plugin.saveConfig()
                EffectEditor.refresh(config)
                gui.openLater(player, EffectsScreen(gui, player))
            } else {
                confirming = "delete"
                redraw()
            }
        }
    }

    private fun number(field: EffectEditor.Field, event: InventoryClickEvent) {
        if (event.click == ClickType.DROP && name != null) {
            EffectEditor.clearNumber(config, name, field)
        } else {
            val step = if (isShift(event)) field.big else field.step
            EffectEditor.setNumber(config, name, field, EffectEditor.number(config, name, field) + direction(event) * step)
        }
        changed()
    }

    private fun color(key: String) {
        val current = name?.let { EffectEditor.color(config, it, key) }?.let(EffectEditor::hex).orEmpty()
        gui.askText(player, "$key color", current, accept = { text ->
            val problem = EffectEditor.setColor(config, name!!, key, text)
            if (problem != null) gui.say(player, problem, NamedTextColor.RED) else {
                gui.plugin.saveConfig()
                EffectEditor.refresh(config)
            }
            gui.openLater(player, EffectScreen(gui, player, name))
        }, cancel = { gui.openLater(player, EffectScreen(gui, player, name)) })
    }

    private fun changed() {
        gui.plugin.saveConfig()
        EffectEditor.refresh(config)
        redraw()
    }

    private companion object {
        val NUMBER_SLOTS = listOf(19, 20, 21, 22, 23, 24)
        val COLOR_SLOTS = mapOf(12 to "trigger", 13 to "from", 14 to "to")
    }
}

private fun preview(name: String, color: Int, type: String, vararg lore: String): ItemStack =
    GuiAssets.decorate(ItemStack(Material.NAME_TAG), name, lore.toList() + "Type: $type").also { stack ->
        stack.editMeta { it.displayName(Component.text(name, TextColor.color(color)).decoration(TextDecoration.ITALIC, false)) }
    }

private fun dye(name: String, color: Int, vararg lore: String): ItemStack =
    GuiAssets.decorate(ItemStack(Material.LEATHER_CHESTPLATE), name, lore.toList()).also { stack ->
        stack.editMeta(LeatherArmorMeta::class.java) { it.setColor(Color.fromRGB(color)) }
    }
