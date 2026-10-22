package io.github.ratr2.repack.edit

import io.github.ratr2.repack.RepackAPI
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * The same edits as the builder screens, for other plugins and for Skript: change a block's settings with chained calls, then save.
 * Nothing is written until save, which checks the block and loads it again at once. If the block could not be opened or created,
 * every call does nothing and save says why.
 *
 * ```java
 * String error = RepackAPI.create("chair", "chair").breakingTime(2.0).tool("axe").dropBlock().save();
 * ```
 */
class BlockBuilder internal constructor(private val draft: Draft?, private val failure: String?) {

    private inline fun edit(change: (Draft) -> Unit): BlockBuilder {
        if (draft != null) change(draft)
        return this
    }

    fun set(path: String, value: Any?) = edit { it.set(path, value) }

    fun model(model: String) = edit { it.set("model", model) }

    fun proxy(block: String) = edit { it.set("proxy.block", block.uppercase()) }

    /** `custom` or `vanilla`. */
    fun mode(mode: String) = edit { it.set("proxy.mode", mode.lowercase()) }

    fun vanillaInteraction(on: Boolean) = edit { it.set("proxy.vanilla-interaction", on) }

    fun breakingTime(seconds: Double) = edit { it.set("breaking.time", seconds) }

    /** `any`, `pickaxe`, `axe`, `shovel`, `hoe`, `sword` or `shears`. */
    fun tool(tool: String) = edit { it.set("breaking.tool", tool.lowercase()) }

    /** `wooden`, `stone`, `iron`, `diamond` or `netherite`, or null for any tool. */
    fun tier(tier: String?) = edit { it.set("breaking.tier", tier?.lowercase()) }

    fun dropsNeedTool(on: Boolean) = edit { it.set("breaking.drops-need-tool", on) }

    fun xp(xp: Int) = edit { it.set("breaking.xp", xp) }

    fun clearDrops() = edit { it.set("drops", emptyList<Any>()) }

    fun dropBlock() = addDrop(linkedMapOf("block" to "self"))

    fun dropItem(item: Material, amount: Int, chance: Double) =
        addDrop(linkedMapOf("item" to item.name, "amount" to amount.coerceAtLeast(1), "chance" to chance.coerceIn(0.0, 1.0)))

    private fun addDrop(drop: Map<String, Any>) = edit { draft ->
        val drops = draft.maps("drops").map { entry -> entry.entries.associate { it.key.toString() to it.value } }.toMutableList()
        drops += drop
        draft.set("drops", drops)
    }

    fun explosionResistant(on: Boolean) = edit { it.set("explosion.resistant", on) }

    fun explosionDrops(on: Boolean) = edit { it.set("explosion.drops", on) }

    /** `block` or `destroy`. */
    fun piston(behavior: String) = edit { it.set("piston", behavior.lowercase()) }

    fun offset(x: Double, y: Double, z: Double) = edit { it.set("display.offset", listOf(x, y, z)) }

    fun scale(x: Double, y: Double, z: Double) = edit { it.set("display.scale", listOf(x, y, z)) }

    fun viewRange(range: Double) = edit { it.set("display.view-range", range) }

    fun brightness(level: Int?) = edit { it.set("display.brightness", level) }

    fun rotateToPlacer(on: Boolean) = edit { it.set("display.rotate-to-placer", on) }

    fun context(context: String) = edit { it.set("display.context", context.lowercase()) }

    fun itemBase(material: Material) = edit { it.set("item.base", material.name) }

    fun itemName(name: String?) = edit { it.set("item.name", name) }

    fun itemLore(lines: List<String>) = edit { it.set("item.lore", lines) }

    fun glint(on: Boolean) = edit { it.set("item.glint", on) }

    fun itemModel(model: String?) = edit { it.set("item.model", model) }

    fun item(item: ItemStack?, overrideModel: Boolean) = edit { it.setItem(item, overrideModel) }

    fun recipe(grid: List<ItemStack?>, exact: Boolean, amount: Int) = edit { it.setRecipe(grid, exact, amount) }

    fun clearRecipe() = edit { it.clearRecipe() }

    fun problem(): String? = failure ?: draft?.problem()

    fun save(): String? = failure ?: RepackAPI.onMain("Repack is not running.") { draft?.save() }
}
