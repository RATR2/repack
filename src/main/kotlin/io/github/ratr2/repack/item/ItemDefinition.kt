package io.github.ratr2.repack.item

import io.github.ratr2.repack.block.BukkitMaterials
import io.github.ratr2.repack.block.DefinitionException
import io.github.ratr2.repack.block.MaterialRules
import io.github.ratr2.repack.model.ModelLibrary
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Base64

class ItemDefinition(
    val id: String,
    val baseName: String,
    val model: String?,
    val name: String?,
    val lore: List<String>,
    val glint: Boolean,
    val stack: ByteArray?,
    val overrideModel: Boolean,
    val components: String?,
) {
    val base: Material by lazy { Material.matchMaterial(baseName)!! }
}

class LoadedItems(val definitions: Map<String, ItemDefinition>, val errors: List<String>)

object ItemDefinitions {

    private val ID = Regex("[a-z0-9_-]+")

    fun load(folder: File, modelIds: Set<String>, only: Set<String>? = null, materials: MaterialRules = BukkitMaterials): LoadedItems {
        val definitions = LinkedHashMap<String, ItemDefinition>()
        val errors = ArrayList<String>()
        val files = folder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val id = file.nameWithoutExtension.lowercase()
            if (only != null && id !in only) continue
            if (!ID.matches(id)) {
                errors += "items/${file.name}: item file names may only use a-z, 0-9, _ and -."
                continue
            }
            try {
                val definition = parse(id, YamlConfiguration().apply { load(file) }, materials)
                if (definition.model != null && definition.model !in modelIds) {
                    errors += "item $id: model '${definition.model}' has no .bbmodel file in the models folder."
                    continue
                }
                definitions[id] = definition
            } catch (e: DefinitionException) {
                errors += "item $id: ${e.message}"
            } catch (e: InvalidConfigurationException) {
                errors += "item $id: not valid YAML (${e.message?.lineSequence()?.firstOrNull()})"
            }
        }
        return LoadedItems(definitions, errors)
    }

    fun parse(id: String, section: ConfigurationSection, materials: MaterialRules = BukkitMaterials): ItemDefinition {
        val base = (section.getString("base") ?: "PAPER").uppercase()
        if (!materials.exists(base)) throw DefinitionException("base $base is not a material.")
        if (!materials.isItem(base)) throw DefinitionException("base $base is not an item.")
        val stack = section.getString("stack")?.takeIf { it.isNotBlank() }?.let {
            try {
                Base64.getDecoder().decode(it.trim())
            } catch (e: IllegalArgumentException) {
                throw DefinitionException("stack is not a saved item.")
            }
        }
        val components = section.getString("components")?.trim()?.takeIf { it.isNotEmpty() }
        if (components != null && !(components.startsWith("[") && components.endsWith("]"))) {
            throw DefinitionException("components must be written like [minecraft:custom_data={a:1}].")
        }
        return ItemDefinition(
            id = id,
            baseName = base,
            model = section.getString("model")?.takeIf { it.isNotBlank() }?.let(ModelLibrary::cleanId),
            name = section.getString("name")?.takeIf { it.isNotEmpty() },
            lore = section.getStringList("lore"),
            glint = section.getBoolean("glint", false),
            stack = stack,
            overrideModel = section.getBoolean("override-model", true),
            components = components,
        )
    }
}
