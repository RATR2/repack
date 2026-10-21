package io.github.ratr2.repack.gui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.view.AnvilView

/**
 * Asks for a line of text with a vanilla anvil: type in the name field, then take the result. Nothing is charged and nothing is
 * given, the paper in the first slot only carries the text.
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
        it.editMeta { meta -> meta.displayName(Component.text(text).decoration(TextDecoration.ITALIC, false)) }
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
        player.closeInventory()
        gui.plugin.server.scheduler.runTask(gui.plugin, Runnable { accept(text) })
    }

    fun closed() {
        if (done) return
        done = true
        gui.plugin.server.scheduler.runTask(gui.plugin, Runnable { cancel() })
    }
}
