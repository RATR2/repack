package io.github.ratr2.repack.gui

import io.github.ratr2.repack.PlayerSettings
import io.github.ratr2.repack.RepackCommand
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

class HubScreen(gui: Gui, player: Player) : Screen(gui, player, 3, "hub", "Repack") {

    override fun render() {
        button(11, "settings", Material.COMPARATOR, "Your settings", "What you see and get told")
        if (player.hasPermission(RepackCommand.ADMIN)) button(12, "entity_builder", Material.ARMOR_STAND, "Entity builder", "Edit model entities")
        if (player.hasPermission(RepackCommand.ADMIN)) button(13, "builder", Material.CRAFTING_TABLE, "Block builder", "Edit custom blocks")
        if (player.hasPermission(RepackCommand.ADMIN)) button(14, "item_builder", Material.DIAMOND, "Item builder", "Edit custom items")
        if (player.hasPermission(RepackCommand.SETTINGS)) button(15, "settings_admin", Material.REPEATER, "Server settings", "The plugin's config.yml")
        button(22, "close", Material.BARRIER, "Close")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        when {
            slot == 11 -> gui.openLater(player, PersonalScreen(gui, player))
            slot == 12 && player.hasPermission(RepackCommand.ADMIN) -> gui.openLater(player, EntityListScreen(gui, player))
            slot == 13 && player.hasPermission(RepackCommand.ADMIN) -> gui.openLater(player, ListScreen(gui, player))
            slot == 14 && player.hasPermission(RepackCommand.ADMIN) -> gui.openLater(player, ItemListScreen(gui, player))
            slot == 15 && player.hasPermission(RepackCommand.SETTINGS) -> gui.openLater(player, ServerScreen(gui, player))
            slot == 22 -> player.closeInventory()
        }
    }
}

class PersonalScreen(gui: Gui, player: Player) : Screen(gui, player, 3, "personal", "Your settings") {

    private val settings = gui.plugin.players
    private val config = gui.plugin.config
    private val admin = player.hasPermission(RepackCommand.ADMIN)

    private fun mine(key: String) = settings.bool(player.uniqueId, key)

    override fun render() {
        choice(11, "Client notices", PlayerSettings.NOTICES, config.getBoolean("client-notices", true), "Chat message when your game version is too old")
        choice(13, "Black loading screen", PlayerSettings.LOADING_SCREEN, config.getBoolean("resource-pack.black-blocks-while-loading", true), "Blocks around you go black while the pack loads")
        if (admin) choice(15, "Build progress bar", PlayerSettings.PROGRESS_BAR, config.getBoolean("workflow.progress-bar", true), "The boss bar while the pack builds")
        back(18) { HubScreen(gui, player) }
    }

    private fun choice(slot: Int, label: String, key: String, serverOn: Boolean, note: String) {
        if (serverOn) toggle(slot, label, mine(key), note) else button(slot, "cross", Material.GRAY_DYE, "$label: off", note, "The server has this switched off")
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        val key = when (slot) {
            11 -> PlayerSettings.NOTICES
            13 -> PlayerSettings.LOADING_SCREEN
            15 -> if (admin) PlayerSettings.PROGRESS_BAR else return
            else -> return
        }
        settings.set(player.uniqueId, key, !mine(key))
        redraw()
    }
}

/** The safe options of `config.yml`. The pack server's address, port and sharing stay in the file, where a typo cannot lock everyone out. */
class ServerScreen(gui: Gui, player: Player) : Screen(gui, player, 4, "server", "Repack server settings") {

    private sealed interface Option {
        val key: String
        val label: String
        val note: String
    }

    private class Flag(override val key: String, override val label: String, override val note: String) : Option
    private class Count(override val key: String, override val label: String, override val note: String, val fallback: Material, val default: Int, val step: Int, val big: Int, val min: Int, val max: Int) : Option
    private class Words(override val key: String, override val label: String, override val note: String, val fallback: Material, val default: String) : Option

    private val options: List<Option> = listOf(
        Flag("client-notices", "Client notices", "Tell players their game version is too old"),
        Flag("resource-pack.join-latest-build", "Join gets the newest build", "Off: only the pack that was last pushed"),
        Flag("resource-pack.invulnerable-while-loading", "Hold while loading", "Players cannot be hurt or move while the pack loads"),
        Flag("resource-pack.black-blocks-while-loading", "Black loading screen", "Blocks around a loading player go black"),
        Count("resource-pack.loading-radius", "Loading radius", "How far the black reaches, up to 16", Material.ENDER_EYE, 8, 1, 4, 1, 16),
        Count("resource-pack.join-delay-ticks", "Join delay (ticks)", "Wait after joining before sending the pack", Material.CLOCK, 40, 10, 100, 0, 600),
        Count("resource-pack.send-gap-ticks", "Send gap (ticks)", "Least time between two packs for one player", Material.CLOCK, 20, 10, 100, 0, 600),
        Flag("workflow.build-on-change", "Build on change", "Rebuild the pack when a model or block file changes"),
        Flag("workflow.progress-bar", "Build progress bar", "Boss bar for operators while the pack builds"),
        Flag("auto-blocks", "Models are blocks", "Off: a model is only a model, and blocks are made in the builder"),
        Flag("gui.textures", "Builder pictures", "Off: plain titles and vanilla items"),
        Flag("debug", "Debug log", "Logs why clicks and mining do or do not work"),
        Words("messages.no-permission", "No permission message", "Sent when a protection plugin says no", Material.NAME_TAG, "You can't do that here."),
    )

    private val config get() = gui.plugin.config

    private fun slotOf(index: Int) = SLOTS[index]

    override fun render() {
        for ((index, option) in options.withIndex()) {
            val slot = slotOf(index)
            when (option) {
                is Flag -> toggle(slot, option.label, config.getBoolean(option.key, true), option.note)
                is Count -> button(slot, "time", option.fallback, "${option.label}: ${config.getInt(option.key, option.default)}", option.note, "Click: +${option.step}, right click: -${option.step}", "Shift: ${option.big}")
                is Words -> button(slot, "name", option.fallback, "${option.label}: ${config.getString(option.key, option.default).orEmpty().ifEmpty { "(none)" }}", option.note, "Click to change, right click to clear")
            }
        }
        back(27) { HubScreen(gui, player) }
    }

    override fun click(slot: Int, event: InventoryClickEvent) {
        if (goBack(slot)) return
        if (!player.hasPermission(RepackCommand.SETTINGS)) return
        val option = options.getOrNull(SLOTS.indexOf(slot)) ?: return
        when (option) {
            is Flag -> config.set(option.key, !config.getBoolean(option.key, true))
            is Count -> {
                val step = if (isShift(event)) option.big else option.step
                config.set(option.key, (config.getInt(option.key, option.default) + direction(event) * step).coerceIn(option.min, option.max))
            }
            is Words -> {
                if (event.isRightClick) config.set(option.key, "") else {
                    gui.askText(player, option.label, config.getString(option.key, option.default).orEmpty(), accept = {
                        config.set(option.key, it)
                        gui.plugin.saveConfig()
                        gui.openLater(player, ServerScreen(gui, player))
                    }, cancel = { gui.openLater(player, ServerScreen(gui, player)) })
                    return
                }
            }
        }
        gui.plugin.saveConfig()
        redraw()
    }

    private companion object {
        val SLOTS = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24)
    }
}
