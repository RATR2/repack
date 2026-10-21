package io.github.ratr2.repack.gui

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.edit.BlockEditor
import io.github.ratr2.repack.edit.Draft
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView
import java.util.UUID

class Gui(val plugin: Repack) : Listener {

    val editor = BlockEditor(plugin)

    private val drafts = HashMap<UUID, MutableMap<String, Draft>>()

    /** Players in the middle of moving from one screen to the next, whose old screen closing is not them leaving. */
    private val navigating = HashSet<UUID>()

    private val inputs = HashMap<UUID, TextInput>()

    val textures: Boolean get() = plugin.config.getBoolean("gui.textures", true)

    fun draftOf(player: Player, id: String): Draft? {
        val mine = drafts.getOrPut(player.uniqueId) { HashMap() }
        mine[id]?.let { return it }
        val fresh = editor.open(id) ?: return null
        mine[id] = fresh
        return fresh
    }

    fun adopt(player: Player, draft: Draft) {
        drafts.getOrPut(player.uniqueId) { HashMap() }[draft.id] = draft
    }

    fun discard(player: Player, id: String) {
        drafts[player.uniqueId]?.remove(id)
    }

    fun open(player: Player, screen: Screen) {
        navigating += player.uniqueId
        try {
            screen.open()
        } finally {
            navigating -= player.uniqueId
        }
    }

    fun openLater(player: Player, screen: Screen) {
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) open(player, screen) })
    }

    fun say(player: Player, text: String, color: NamedTextColor = NamedTextColor.GREEN) {
        player.sendMessage(Component.text(text, color))
    }

    fun askText(player: Player, title: String, initial: String, accept: (String) -> Unit, cancel: () -> Unit) {
        inputs[player.uniqueId]?.let { inputs.remove(player.uniqueId) }
        val input = TextInput(this, player, title, initial, accept, cancel)
        inputs[player.uniqueId] = input
        navigating += player.uniqueId
        plugin.server.scheduler.runTask(plugin, Runnable {
            try {
                input.open()
            } finally {
                navigating -= player.uniqueId
            }
        })
    }

    // Every click in one of our screens is cancelled and handed to the screen; nothing is ever moved around.

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        inputs[player.uniqueId]?.let { input ->
            if (event.view.topInventory.type == InventoryType.ANVIL) {
                event.isCancelled = true
                if (event.rawSlot == 2) input.confirm(event.view as? AnvilView)
                return
            }
        }
        val screen = event.view.topInventory.holder as? Screen ?: return
        // Screens that take an item from the cursor let the player pick one up in their own inventory (only picking up and putting back).
        if (screen.takesCursor(event) && event.clickedInventory != null && event.clickedInventory != event.view.topInventory && event.action in CURSOR_ACTIONS) return
        event.isCancelled = true
        if (event.clickedInventory == event.view.topInventory) screen.click(event.slot, event) else if (event.clickedInventory != null) screen.clickBottom(event)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Screen) event.isCancelled = true
    }

    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val player = event.view.player as? Player ?: return
        inputs[player.uniqueId]?.prepare(event)
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val holder = event.inventory.holder
        if (player.uniqueId in navigating) return
        if (event.inventory.type == InventoryType.ANVIL) inputs.remove(player.uniqueId)?.closed()
        if (holder is Screen) holder.onClose()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        drafts.remove(event.player.uniqueId)
        inputs.remove(event.player.uniqueId)
    }

    private companion object {
        val CURSOR_ACTIONS = setOf(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR,
        )
    }

    internal fun finishInput(player: Player) {
        inputs.remove(player.uniqueId)
    }
}

abstract class Screen(protected val gui: Gui, val player: Player, private val rows: Int, private val name: String, private val fallbackTitle: String) : InventoryHolder {

    private lateinit var inv: Inventory

    override fun getInventory(): Inventory = inv

    fun open() {
        inv = Bukkit.createInventory(this, rows * 9, GuiAssets.title(name, fallbackTitle, gui.textures))
        render()
        player.openInventory(inv)
    }

    protected fun redraw() {
        inv.clear()
        render()
    }

    protected abstract fun render()

    open fun click(slot: Int, event: InventoryClickEvent) {}

    open fun clickBottom(event: InventoryClickEvent) {}

    open fun onClose() {}

    open fun takesCursor(event: InventoryClickEvent): Boolean = false

    protected fun put(slot: Int, item: ItemStack?) = inv.setItem(slot, item)

    protected fun button(slot: Int, icon: String, fallback: Material, name: String, vararg lore: String) {
        put(slot, GuiAssets.button(icon, fallback, name, lore.toList(), gui.textures))
    }

    protected fun toggle(slot: Int, label: String, value: Boolean, vararg lore: String) {
        button(slot, if (value) "check" else "cross", if (value) Material.LIME_DYE else Material.GRAY_DYE, "$label: ${if (value) "on" else "off"}", "Click to switch", *lore)
    }

    protected fun back(slot: Int, target: () -> Screen) {
        button(slot, "back", Material.ARROW, "Back")
        backs[slot] = target
    }

    private val backs = HashMap<Int, () -> Screen>()

    protected fun goBack(slot: Int): Boolean {
        val target = backs[slot] ?: return false
        gui.openLater(player, target())
        return true
    }

    protected fun isShift(event: InventoryClickEvent) = event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT

    protected fun direction(event: InventoryClickEvent) = if (event.isRightClick) -1 else 1
}
