package io.github.ratr2.repack.entity

import io.github.ratr2.repack.block.DefinitionException
import io.github.ratr2.repack.block.DisplayContext
import io.github.ratr2.repack.model.ModelLibrary
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/** One kind of model entity, read from `entities/<id>.yml`: a model shown in the world at any position, with an animation that can change. */
class EntityDefinition(
    val id: String,
    /** The model in `models/`, by id. */
    val model: String,
    /** The animation of a model with meshes that plays when the entity appears, by name (lowercase), or null for the still model. */
    val animation: String?,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    /** Blocks from the entity's position to the middle of the model. */
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    /** Multiplier on the client's display entity view distance (1.0 is 64 blocks). */
    val viewRange: Float,
    /** Fixed light level 0 to 15, or null to light it like the world around it. */
    val brightness: Int?,
    val context: DisplayContext,
)

class LoadedEntities(val definitions: Map<String, EntityDefinition>, val errors: List<String>)

object EntityDefinitions {

    private val ID = Regex("[a-z0-9_-]+")
    private const val MAX_VIEW_RANGE = 100.0
    private const val MAX_SCALE = 1000.0

    fun load(folder: File, modelIds: Set<String>): LoadedEntities {
        val definitions = LinkedHashMap<String, EntityDefinition>()
        val errors = ArrayList<String>()
        val files = folder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val id = file.nameWithoutExtension.lowercase()
            if (!ID.matches(id)) {
                errors += "entities/${file.name}: file names may only use a-z, 0-9, _ and -."
                continue
            }
            try {
                val definition = parse(id, YamlConfiguration().apply { load(file) })
                if (definition.model !in modelIds) {
                    errors += "entity $id: model '${definition.model}' has no .bbmodel file in the models folder."
                    continue
                }
                definitions[id] = definition
            } catch (e: DefinitionException) {
                errors += "entity $id: ${e.message}"
            } catch (e: InvalidConfigurationException) {
                errors += "entity $id: not valid YAML (${e.message?.lineSequence()?.firstOrNull()})"
            }
        }
        return LoadedEntities(definitions, errors)
    }

    fun parse(id: String, section: ConfigurationSection): EntityDefinition {
        val model = section.getString("model")?.takeIf { it.isNotBlank() }?.let(ModelLibrary::cleanId) ?: throw DefinitionException("needs a model: the name of a file in models/.")
        val scale = numbers(section, "scale", 3, "scale")
        val offset = numbers(section, "offset", 3, "offset", allowSingle = false)
        if (scale.any { !it.isFinite() || it == 0.0 || kotlin.math.abs(it) > MAX_SCALE }) throw DefinitionException("scale must be numbers that are not 0, at most $MAX_SCALE.")
        if (offset.any { !it.isFinite() }) throw DefinitionException("offset must be three numbers.")
        val viewRange = section.getDouble("view-range", 1.0)
        if (!viewRange.isFinite() || viewRange <= 0 || viewRange > MAX_VIEW_RANGE) throw DefinitionException("view-range must be above 0 and at most $MAX_VIEW_RANGE.")
        val brightness = if (section.contains("brightness")) section.getInt("brightness", -1).takeIf { it >= 0 } else null
        if (brightness != null && brightness > 15) throw DefinitionException("brightness must be 0 to 15.")
        val context = try {
            DisplayContext.valueOf((section.getString("context") ?: "none").uppercase())
        } catch (e: IllegalArgumentException) {
            throw DefinitionException("context '${section.getString("context")}' is not one of ${DisplayContext.entries.joinToString { it.name.lowercase() }}.")
        }
        return EntityDefinition(
            id = id,
            model = model,
            animation = section.getString("animation")?.takeIf { it.isNotBlank() }?.let(ModelLibrary::cleanId),
            scaleX = scale[0].toFloat(), scaleY = scale[1].toFloat(), scaleZ = scale[2].toFloat(),
            offsetX = offset[0], offsetY = offset[1], offsetZ = offset[2],
            viewRange = viewRange.toFloat(),
            brightness = brightness,
            context = context,
        )
    }

    /** [count] numbers from a list, or with [allowSingle] one number used for all of them; [default] 1 for scale and 0 for the rest. */
    private fun numbers(section: ConfigurationSection, key: String, count: Int, name: String, allowSingle: Boolean = true): List<Double> {
        val fallback = if (key == "scale") 1.0 else 0.0
        val value = section.get(key) ?: return List(count) { fallback }
        if (allowSingle && value is Number) return List(count) { value.toDouble() }
        val list = section.getDoubleList(key)
        if (list.size != count) throw DefinitionException("$name needs ${if (allowSingle) "one number or " else ""}$count numbers: x, y, z.")
        return list
    }
}
