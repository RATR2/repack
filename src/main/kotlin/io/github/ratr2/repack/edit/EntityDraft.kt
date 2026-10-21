package io.github.ratr2.repack.edit

import io.github.ratr2.repack.block.DefinitionException
import io.github.ratr2.repack.entity.EntityDefinitions
import org.bukkit.configuration.file.YamlConfiguration

/** The settings of one model entity type being edited. Nothing reaches `entities/<id>.yml` until [save]. */
class EntityDraft internal constructor(
    val id: String,
    private val yaml: YamlConfiguration,
    private val writer: (String, YamlConfiguration) -> String?,
) {

    fun string(path: String, default: String): String = yaml.getString(path) ?: default

    fun double(path: String, default: Double): Double = yaml.getDouble(path, default)

    fun int(path: String, default: Int): Int = yaml.getInt(path, default)

    fun has(path: String): Boolean = yaml.contains(path, true)

    fun doubles(path: String): List<Double> = yaml.getDoubleList(path)

    fun get(path: String): Any? = yaml.get(path)

    fun set(path: String, value: Any?): EntityDraft {
        yaml.set(path, value)
        return this
    }

    fun problem(): String? = try {
        EntityDefinitions.parse(id, yaml)
        null
    } catch (e: DefinitionException) {
        e.message
    }

    fun save(): String? = problem() ?: writer(id, yaml)

    fun text(): String = yaml.saveToString()
}
