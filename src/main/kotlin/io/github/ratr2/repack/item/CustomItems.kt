package io.github.ratr2.repack.item

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.model.ItemModelGenerator
import io.github.ratr2.repack.text.Colors
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import net.kyori.adventure.key.Key
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

class CustomItems(private val plugin: Repack) {

    private val idKey = NamespacedKey(plugin, "item")

    @Volatile
    var definitions: Map<String, ItemDefinition> = emptyMap()

    val ids: Set<String> get() = definitions.keys

    fun definitionOf(id: String): ItemDefinition? = definitions[id]

    fun create(definition: ItemDefinition, amount: Int = 1): ItemStack {
        val saved = definition.stack?.let { ItemStack.deserializeBytes(it) }
        val item = saved ?: ItemStack(definition.base)
        item.amount = amount.coerceIn(1, 99)
        val modelKey = definition.modelKey
        if (modelKey != null && (saved == null || definition.overrideModel)) {
            item.setData(DataComponentTypes.ITEM_MODEL, Key.key(ItemModelGenerator.NAMESPACE, modelKey))
        }
        if (saved == null) {
            val name = definition.name ?: definition.id.split('_', '-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            item.setData(DataComponentTypes.ITEM_NAME, Colors.parse(name))
            if (definition.lore.isNotEmpty()) item.setData(DataComponentTypes.LORE, ItemLore.lore(definition.lore.map { Colors.plain(it) }))
            if (definition.glint) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        }
        var result = item
        if (definition.components != null) {
            result = try {
                @Suppress("DEPRECATION")
                Bukkit.getUnsafe().modifyItemStack(item, definition.components)
            } catch (e: RuntimeException) {
                plugin.logger.warning("item ${definition.id}: the components could not be applied: ${e.message}")
                item
            }
        }
        result.editPersistentDataContainer { it.set(idKey, PersistentDataType.STRING, definition.id) }
        return result
    }

    fun idOf(item: ItemStack?): String? {
        if (item == null || item.isEmpty) return null
        return item.persistentDataContainer.get(idKey, PersistentDataType.STRING)
    }
}
