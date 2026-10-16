package io.github.ratr2.repack.block

import io.github.ratr2.repack.Repack
import io.papermc.paper.event.player.PlayerPickItemEvent
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/**
 * Middle click on a custom block gives the block's own item instead of its proxy block (a barrier, say). In survival that is
 * the item from the inventory, in creative a new one, in the hotbar like vanilla.
 */
class PickBlock(private val plugin: Repack) : Listener {

    @EventHandler(ignoreCancelled = true)
    fun onPick(event: PlayerPickItemEvent) {
        // Newer Paper versions send this for entities too; those are not ours.
        if (event.javaClass.simpleName == "PlayerPickEntityEvent") return
        val player = event.player
        val block = player.rayTraceBlocks(reachOf(player).toDouble())?.hitBlock ?: return
        val id = plugin.blocks.idAt(block) ?: return
        val definition = plugin.blocks.definitionOf(id) ?: return
        event.isCancelled = true

        val inventory = player.inventory
        val inHotbar = (0..8).firstOrNull { plugin.items.idOf(inventory.getItem(it)) == id }
        if (inHotbar != null) {
            inventory.heldItemSlot = inHotbar
            return
        }
        if (player.gameMode == GameMode.CREATIVE) {
            val slot = if (inventory.getItem(inventory.heldItemSlot)?.isEmpty != false) {
                inventory.heldItemSlot
            } else {
                (0..8).firstOrNull { inventory.getItem(it)?.isEmpty != false } ?: inventory.heldItemSlot
            }
            inventory.setItem(slot, plugin.items.create(definition))
            inventory.heldItemSlot = slot
            return
        }
        val from = (9..35).firstOrNull { plugin.items.idOf(inventory.getItem(it)) == id } ?: return
        val held = inventory.heldItemSlot
        val picked = inventory.getItem(from)
        inventory.setItem(from, inventory.getItem(held))
        inventory.setItem(held, picked)
    }
}
