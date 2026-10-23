package io.github.ratr2.repack.item

import io.github.ratr2.repack.block.BukkitMaterials
import io.github.ratr2.repack.block.DefinitionException
import io.github.ratr2.repack.block.MaterialRules
import io.github.ratr2.repack.model.DisplayTransform
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
    /** A PNG in the items folder that is the item's picture: a flat item, without a .bbmodel. */
    val texture: String? = null,
    /** How the flat picture is held: `generated` (an item) or `handheld` (a tool, held out at an angle). */
    val parent: String = "generated",
    /** What to change about how the item is drawn in a hand, on the ground, in a frame and in an inventory, by display context. */
    val display: Map<String, DisplayTransform> = emptyMap(),
) {
    val base: Material by lazy { Material.matchMaterial(baseName)!! }

    /** An item with a picture or its own display settings has an item model of its own, made for it. */
    val ownModel: Boolean get() = texture != null || display.isNotEmpty()

    /** The id of the item model the item uses: its own, the .bbmodel's, or none (the base item's). */
    val modelKey: String? get() = if (ownModel) "$OWN_FOLDER/$id" else model

    companion object {
        const val OWN_FOLDER = "custom"
    }
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
                val definition = parse(id, YamlConfiguration().apply { load(file) }, materials, folder)
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

    /** The display contexts a model can be drawn in. `on_shelf` is left out: only game versions from 1.21.9 know it. */
    val DISPLAY_CONTEXTS = listOf("gui", "ground", "fixed", "head", "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand")

    private val HELD = setOf("generated", "handheld")

    /** With a folder, the picture named by `texture` (or else `<id>.png` next to the file) is looked for there. */
    fun parse(id: String, section: ConfigurationSection, materials: MaterialRules = BukkitMaterials, folder: File? = null): ItemDefinition {
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
        val model = section.getString("model")?.takeIf { it.isNotBlank() }?.let(ModelLibrary::cleanId)
        val named = section.getString("texture")?.trim()?.takeIf { it.isNotEmpty() }
        val texture = named ?: if (model == null && folder != null && File(folder, "$id.png").isFile) "$id.png" else null
        if (texture != null && model != null) throw DefinitionException("use either a model or a texture, not both.")
        if (texture != null && folder != null) {
            val file = File(folder, texture)
            if (file.canonicalFile.parentFile != folder.canonicalFile) throw DefinitionException("texture must be a file in the items folder.")
            if (!texture.endsWith(".png", ignoreCase = true)) throw DefinitionException("texture '$texture' must be a .png file.")
            if (!file.isFile) throw DefinitionException("texture '$texture' is not a file in the items folder.")
        }
        val parent = section.getString("parent", "generated")!!.lowercase().trim()
        if (parent !in HELD) throw DefinitionException("parent must be generated or handheld.")
        val display = parseDisplay(section)
        if (display.isNotEmpty() && model == null && texture == null) throw DefinitionException("display changes how a model or a texture is drawn: add a model or a texture.")
        return ItemDefinition(
            id = id,
            baseName = base,
            model = model,
            name = section.getString("name")?.takeIf { it.isNotEmpty() },
            lore = section.getStringList("lore"),
            glint = section.getBoolean("glint", false),
            stack = stack,
            overrideModel = section.getBoolean("override-model", true),
            components = components,
            texture = texture,
            parent = parent,
            display = display,
        )
    }

    private fun parseDisplay(section: ConfigurationSection): Map<String, DisplayTransform> {
        val block = section.getConfigurationSection("display") ?: return emptyMap()
        val result = LinkedHashMap<String, DisplayTransform>()
        for (context in block.getKeys(false)) {
            if (context !in DISPLAY_CONTEXTS) throw DefinitionException("display.$context is not a place an item is drawn. They are ${DISPLAY_CONTEXTS.joinToString()}.")
            val entry = block.getConfigurationSection(context) ?: throw DefinitionException("display.$context needs rotation, translation and scale.")
            fun vector(key: String): List<Double>? {
                if (!entry.contains(key)) return null
                val values = entry.getList(key)?.map { (it as? Number)?.toDouble() }
                if (values == null || values.size != 3 || values.any { it == null || !it.isFinite() }) throw DefinitionException("display.$context.$key is three numbers, like [0, 90, 0].")
                return values.map { it!! }
            }
            val unknown = entry.getKeys(false) - setOf("rotation", "translation", "scale")
            if (unknown.isNotEmpty()) throw DefinitionException("display.$context: unknown setting ${unknown.first()}. Use rotation, translation and scale.")
            result[context] = DisplayTransform(vector("rotation"), vector("translation"), vector("scale"))
        }
        return result
    }
}
