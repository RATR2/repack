package io.github.ratr2.repack.gui

import io.github.ratr2.repack.text.Colors
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView

/**
 * Asks for a line of text with a vanilla anvil: type in the name field, then take the result. Nothing is charged; the paper in
 * the first slot only carries the text and is emptied before the anvil closes. `&c`, `&#rrggbb` and `<#rrggbb>` codes show as
 * typed.
 */
class TextInput(
    private val gui: Gui,
    private val player: Player,
    private val title: String,
    private val initial: String,
    private val accept: (String) -> Unit,
    private val cancel: () -> Unit,
) {

    private var done = false

    fun open() {
        val view = player.openAnvil(null, true) ?: return
        view.topInventory.setItem(0, paper(initial.ifEmpty { title }))
    }

    private fun paper(text: String) = ItemStack(Material.PAPER).also {
        it.editMeta { meta -> meta.displayName(Colors.plain(text)) }
    }

    fun prepare(event: PrepareAnvilEvent) {
        val view = event.view
        val text = view.renameText?.takeIf { it.isNotBlank() } ?: initial.ifEmpty { title }
        event.result = paper(text)
        view.repairCost = 0
    }

    fun confirm(view: AnvilView?) {
        if (done) return
        val text = view?.renameText?.trim().orEmpty().ifEmpty { initial }
        done = true
        gui.finishInput(player)
        view?.topInventory?.clear()
        player.closeInventory()
        gui.plugin.server.scheduler.runTask(gui.plugin, Runnable { accept(text) })
    }

    fun closed() {
        if (done) return
        done = true
        gui.plugin.server.scheduler.runTask(gui.plugin, Runnable { cancel() })
    }
}
