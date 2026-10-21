package io.github.ratr2.repack.edit

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.config.ConfigMigrator
import io.github.ratr2.repack.entity.EntityTemplate
import io.github.ratr2.repack.model.ModelLibrary
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Changes entity files from inside the game and from code, the way [ItemEditor] does for items. */
class EntityEditor(private val plugin: Repack) {

    private val idPattern = Regex("[a-z0-9_-]+")

    private fun fileOf(id: String) = File(plugin.entitiesFolder, "$id.yml")

    /** The draft of an existing entity type, or null when there is none. */
    fun open(id: String): EntityDraft? {
        if (!idPattern.matches(id) || id !in plugin.modelEntities.definitions) return null
        val file = fileOf(id)
        val yaml = if (file.isFile) YamlConfiguration().apply { load(file) } else return null
        return EntityDraft(id, yaml, ::write)
    }

    /** A draft for a new entity type that shows [model], or a message saying why that cannot be. */
    fun create(id: String, model: String): Result<EntityDraft> {
        if (!idPattern.matches(id)) return Result.failure(IllegalArgumentException("Entity ids use only a-z, 0-9, _ and -."))
        if (id in plugin.modelEntities.definitions || fileOf(id).exists()) return Result.failure(IllegalArgumentException("An entity with the id '$id' already exists."))
        val cleaned = ModelLibrary.cleanId(model)
        if (cleaned !in ModelLibrary.ids(plugin.modelsFolder)) return Result.failure(IllegalArgumentException("There is no model file '$cleaned.bbmodel'."))
        return Result.success(EntityDraft(id, YamlConfiguration().apply { loadFromString(EntityTemplate.render(id, cleaned)) }, ::write))
    }

    private fun write(id: String, yaml: YamlConfiguration): String? {
        val file = fileOf(id)
        return try {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ConfigMigrator.withVersion(yaml, EntityTemplate.version).saveToString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            plugin.reloadEntities().firstOrNull { it.startsWith("entity $id:") }
        } catch (e: java.io.IOException) {
            "Could not write ${file.name}: ${e.message}"
        }
    }

    /** Moves the entity type's file into `deleted/` and forgets it. The entities already in the world stay saved but are not shown. */
    fun delete(id: String): String? {
        if (!idPattern.matches(id) || id !in plugin.modelEntities.definitions) return "There is no entity '$id'."
        return try {
            val file = fileOf(id)
            if (file.isFile) {
                val trash = File(plugin.dataFolder, "deleted/${System.currentTimeMillis()}-entity-$id").apply { mkdirs() }
                Files.move(file.toPath(), File(trash, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            plugin.reloadEntities()
            null
        } catch (e: java.io.IOException) {
            "Could not delete '$id': ${e.message}"
        }
    }
}
