package io.github.ratr2.repack.gui

import io.github.ratr2.repack.model.ItemModelGenerator
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.nio.ByteBuffer

object GuiAssets {

    val SCREENS = listOf("list", "models", "overview", "proxy", "breaking", "drops", "rules", "display", "item", "recipe", "hub", "personal", "server")

    val ICONS = listOf(
        "back", "next", "previous", "close", "new", "save", "reset", "check", "cross",
        "breaking", "drops", "rules", "display", "item", "recipe",
        "mode", "time", "tool", "tier", "xp", "piston",
        "block", "add",
        "offset", "scale", "view", "light", "context",
        "name", "lore", "category",
        "settings", "settings_admin",
    )

    val FONT: Key = Key.key("repack", "gui")

    const val PAD = 8

    /** Moves the cursor 8 pixels left: titles start 8 pixels in, the pictures start at the edge. */
    const val SHIFT_LEFT = ''

    fun glyph(screen: String): Char = '' + SCREENS.indexOf(screen)

    private fun resource(path: String): ByteArray? = GuiAssets::class.java.getResourceAsStream("/gui/$path")?.use { it.readBytes() }

    fun screenPng(name: String): ByteArray? = resource("screens/$name.png")

    fun iconPng(name: String): ByteArray? = resource("icons/$name.png")

    fun iconMeta(name: String): ByteArray? = resource("icons/$name.png.mcmeta")

    fun hasScreen(name: String) = screenPng(name) != null

    fun hasIcon(name: String) = iconPng(name) != null

    fun sizeOf(png: ByteArray): Pair<Int, Int> {
        val buffer = ByteBuffer.wrap(png, 16, 8)
        return buffer.int to buffer.int
    }

    fun title(screen: String, fallback: String, textures: Boolean): Component =
        if (textures && hasScreen(screen)) {
            Component.text("$SHIFT_LEFT${glyph(screen)}").font(FONT).color(NamedTextColor.WHITE)
        } else {
            Component.text(fallback)
        }

    fun button(icon: String, fallback: Material, name: String, lore: List<String> = emptyList(), textures: Boolean = true): ItemStack =
        decorate(if (textures && hasIcon(icon)) iconItem(icon) else ItemStack(fallback), name, lore)

    fun decorate(item: ItemStack, name: String, lore: List<String> = emptyList()): ItemStack {
        item.editMeta { meta ->
            meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { Component.text(it, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false) })
            }
            meta.addItemFlags(*org.bukkit.inventory.ItemFlag.entries.toTypedArray())
        }
        return item
    }

    private fun iconItem(icon: String): ItemStack = ItemStack(Material.PAPER).also {
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key(ItemModelGenerator.NAMESPACE, "gui/$icon"))
    }
}
