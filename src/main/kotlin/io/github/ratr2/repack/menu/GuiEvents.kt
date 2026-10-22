package io.github.ratr2.repack.menu

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.inventory.ClickType

/*
 * What other plugins hear about Repack's custom GUIs. Slot rules (costs, cooldowns, permissions) are theirs to decide here: cancel a
 * GuiClickEvent and nothing of the slot's actions runs.
 */

/** A player clicked a slot of a custom GUI. key is the slot's layout character or index, null when nothing is defined there. */
class GuiClickEvent(
    val player: Player,
    val gui: LiveGui,
    val slot: Int,
    val key: SlotKey?,
    val click: ClickType,
    val tags: Map<String, String>,
) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled() = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}

class GuiOpenEvent(val player: Player, val gui: LiveGui) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}

/** A player closed a custom GUI. Cancelling it opens the GUI again for them, as long as they are not leaving the server. */
class GuiCloseEvent(val player: Player, val gui: LiveGui) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled() = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}

/** An `event: name` action ran. inputs holds what a dialog or anvil collected (the anvil's text is "input"). */
class GuiActionEvent(val player: Player, val gui: LiveGui?, val name: String, val inputs: Map<String, String>) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        @JvmStatic
        val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList() = HANDLERS
    }
}
