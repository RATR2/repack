package io.github.ratr2.repack.block

import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.block.Action
import org.bukkit.inventory.EquipmentSlot

/** Fired when a player clicks the block of a custom block. Cancelling it cancels the click. */
class CustomBlockInteractEvent(
    val player: Player,
    val block: Block,
    val id: String,
    val action: Action,
    val hand: EquipmentSlot?,
) : Event(), Cancellable {

    private var cancelled = false

    override fun isCancelled() = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    override fun getHandlers() = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
