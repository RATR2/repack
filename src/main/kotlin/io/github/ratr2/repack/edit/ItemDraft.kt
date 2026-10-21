package io.github.ratr2.repack.edit

import io.github.ratr2.repack.block.BukkitMaterials
import io.github.ratr2.repack.block.DefinitionException
import io.github.ratr2.repack.item.ItemDefinitions
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack

class ItemDraft internal constructor(
    val id: String,
    private val yaml: YamlConfiguration,
    private val writer: (String, YamlConfiguration) -> String?,
) {

    fun string(path: String, default: String): String = yaml.getString(path) ?: default

    fun bool(path: String, default: Boolean): Boolean = yaml.getBoolean(path, default)

    fun strings(path: String): List<String> = yaml.getStringList(path)

    fun set(path: String, value: Any?): ItemDraft {
        yaml.set(path, value)
        return this
    }

    fun setStack(stack: ItemStack?, overrideModel: Boolean = true): ItemDraft {
        yaml.set("stack", stack?.takeUnless { it.isEmpty }?.let(ItemCodec::encode))
        yaml.set("override-model", overrideModel)
        return this
    }

    fun stack(): ItemStack? = yaml.getString("stack")?.let { runCatching { ItemCodec.decode(it) }.getOrNull() }

    fun overrideModel(): Boolean = yaml.getBoolean("override-model", true)

    fun problem(): String? = try {
        ItemDefinitions.parse(id, yaml, BukkitMaterials)
        null
    } catch (e: DefinitionException) {
        e.message
    }

    fun definition(): io.github.ratr2.repack.item.ItemDefinition? = try {
        ItemDefinitions.parse(id, yaml, BukkitMaterials)
    } catch (e: DefinitionException) {
        null
    }

    fun save(): String? = problem() ?: writer(id, yaml)

    fun text(): String = yaml.saveToString()
}
