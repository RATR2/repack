package io.github.ratr2.repack.block

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.model.ItemModelGenerator
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** The item a player holds to place a custom block. It shows the block's model in the inventory and in the hand. */
class BlockItems(plugin: Repack) {

    private val idKey = NamespacedKey(plugin, "block")
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun create(definition: BlockDefinition, amount: Int = 1): ItemStack {
        val item = ItemStack(definition.item.base, amount.coerceIn(1, 99))
        item.setData(DataComponentTypes.ITEM_MODEL, Key.key(ItemModelGenerator.NAMESPACE, definition.item.model ?: definition.model))
        val name = definition.item.name ?: definition.id.split('_', '-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        item.setData(DataComponentTypes.ITEM_NAME, legacy.deserialize(name))
        if (definition.item.lore.isNotEmpty()) {
            item.setData(
                DataComponentTypes.LORE,
                io.papermc.paper.datacomponent.item.ItemLore.lore(
                    definition.item.lore.map { legacy.deserialize(it).decoration(TextDecoration.ITALIC, false) as Component }
                ),
            )
        }
        if (definition.item.glint) item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        item.editPersistentDataContainer { it.set(idKey, PersistentDataType.STRING, definition.id) }
        return item
    }

    /** The custom block id an item places, or null for any other item. */
    fun idOf(item: ItemStack?): String? {
        if (item == null || item.isEmpty) return null
        return item.persistentDataContainer.get(idKey, PersistentDataType.STRING)
    }
}
