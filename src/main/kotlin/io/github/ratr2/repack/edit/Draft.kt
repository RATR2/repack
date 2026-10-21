package io.github.ratr2.repack.edit

import io.github.ratr2.repack.block.BlockDefinitions
import io.github.ratr2.repack.block.DefinitionException
import io.github.ratr2.repack.block.MaterialRules
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack

class Draft internal constructor(
    val id: String,
    private val yaml: YamlConfiguration,
    private val materials: MaterialRules,
    private val writer: (String, YamlConfiguration) -> String?,
) {

    fun get(path: String): Any? = yaml.get(path)

    fun string(path: String, default: String): String = yaml.getString(path) ?: default

    fun double(path: String, default: Double): Double = yaml.getDouble(path, default)

    fun int(path: String, default: Int): Int = yaml.getInt(path, default)

    fun bool(path: String, default: Boolean): Boolean = yaml.getBoolean(path, default)

    fun has(path: String): Boolean = yaml.contains(path, true)

    fun strings(path: String): List<String> = yaml.getStringList(path)

    fun maps(path: String): List<Map<*, *>> = yaml.getMapList(path)

    fun doubles(path: String): List<Double> = yaml.getDoubleList(path)

    fun set(path: String, value: Any?): Draft {
        yaml.set(path, value)
        return this
    }

    fun setItem(stack: ItemStack?, overrideModel: Boolean = true): Draft {
        yaml.set("item.stack", stack?.takeUnless { it.isEmpty }?.let(ItemCodec::encode))
        yaml.set("item.override-model", overrideModel)
        return this
    }

    fun item(): ItemStack? = yaml.getString("item.stack")?.let { runCatching { ItemCodec.decode(it) }.getOrNull() }

    fun overrideModel(): Boolean = yaml.getBoolean("item.override-model", true)

    fun setRecipe(grid: List<ItemStack?>, allExact: Boolean, amount: Int, group: String? = null, category: String? = null): Draft {
        require(grid.size == 9) { "A recipe grid has nine slots." }
        val used = grid.mapIndexedNotNull { index, stack -> if (stack == null || stack.isEmpty) null else index }
        if (used.isEmpty()) return clearRecipe()
        val rows = used.map { it / 3 }.let { it.min()..it.max() }
        val columns = used.map { it % 3 }.let { it.min()..it.max() }

        val chars = ArrayList<Pair<ItemStack, Char>>()
        val shape = rows.map { row ->
            columns.map { column ->
                val stack = grid[row * 3 + column]?.takeUnless { it.isEmpty } ?: return@map ' '
                val known = chars.firstOrNull { it.first.isSimilar(stack) }
                known?.second ?: ('a' + chars.size).also { chars += stack.asOne() to it }
            }.joinToString("")
        }

        yaml.set("recipe.shape", shape)
        yaml.set("recipe.ingredients", null)
        yaml.set("recipe.exact", null)
        for ((stack, char) in chars) {
            if (allExact || !ItemCodec.isPlain(stack)) yaml.set("recipe.exact.$char", ItemCodec.encode(stack))
            else yaml.set("recipe.ingredients.$char", stack.type.name)
        }
        yaml.set("recipe.amount", amount.coerceIn(1, 64))
        yaml.set("recipe.group", group)
        yaml.set("recipe.category", category)
        return this
    }

    fun clearRecipe(): Draft {
        yaml.set("recipe", null)
        return this
    }

    fun recipeGrid(): List<ItemStack?> {
        val grid = arrayOfNulls<ItemStack>(9)
        val shape = yaml.getStringList("recipe.shape")
        for ((row, text) in shape.withIndex()) {
            for ((column, char) in text.withIndex()) {
                if (char == ' ' || row > 2 || column > 2) continue
                val exact = yaml.getString("recipe.exact.$char")
                grid[row * 3 + column] = if (exact != null) {
                    runCatching { ItemCodec.decode(exact) }.getOrNull()
                } else {
                    yaml.getString("recipe.ingredients.$char")?.let { org.bukkit.Material.matchMaterial(it) }?.let(::ItemStack)
                }
            }
        }
        return grid.toList()
    }

    fun problem(): String? = try {
        BlockDefinitions.parse(id, yaml, materials)
        null
    } catch (e: DefinitionException) {
        e.message
    }

    fun save(): String? = problem() ?: writer(id, yaml)

    fun text(): String = yaml.saveToString()
}
