package io.github.ratr2.repack.block

import io.github.ratr2.repack.Repack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class BlockMenu(private val plugin: Repack) : Listener {

    private class Holder(val page: Int) : InventoryHolder {
        lateinit var view: Inventory
        override fun getInventory() = view
    }

    fun open(player: Player, page: Int = 0) {
        val ids = plugin.giveIds()
        val pages = maxOf(1, (ids.size + PER_PAGE - 1) / PER_PAGE)
        val current = page.coerceIn(0, pages - 1)
        val holder = Holder(current)
        val view = Bukkit.createInventory(holder, SIZE, Component.text("Custom blocks and items (${current + 1}/$pages)"))
        holder.view = view
        ids.drop(current * PER_PAGE).take(PER_PAGE).forEachIndexed { slot, id ->
            val icon = plugin.stackFor(id) ?: return@forEachIndexed
            icon.editMeta { meta ->
                val lore = meta.lore()?.toMutableList() ?: mutableListOf()
                lore += Component.empty()
                lore += hint("repack:$id", NamedTextColor.DARK_GRAY)
                lore += hint("Click: take 1, shift-click: take 64", NamedTextColor.GRAY)
                meta.lore(lore)
            }
            view.setItem(slot, icon)
        }
        if (current > 0) view.setItem(PREVIOUS, button(Material.ARROW, "Previous page"))
        if (current < pages - 1) view.setItem(NEXT, button(Material.ARROW, "Next page"))
        player.openInventory(view)
    }

    private fun hint(text: String, color: NamedTextColor) = Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun button(material: Material, name: String) =
        ItemStack(material).also { item -> item.editMeta { it.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) } }

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory != event.view.topInventory) return
        when (val slot = event.slot) {
            PREVIOUS -> if (holder.page > 0) open(player, holder.page - 1)
            NEXT -> if (event.currentItem != null) open(player, holder.page + 1)
            else -> {
                if (slot >= PER_PAGE) return
                val id = plugin.items.idOf(event.currentItem) ?: plugin.customItems.idOf(event.currentItem) ?: return
                val amount = if (event.click == ClickType.SHIFT_LEFT || event.click == ClickType.SHIFT_RIGHT) 64 else 1
                player.inventory.addItem(plugin.stackFor(id, amount) ?: return).values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private companion object {
        const val SIZE = 54
        const val PER_PAGE = 45
        const val PREVIOUS = 48
        const val NEXT = 50
    }
}
