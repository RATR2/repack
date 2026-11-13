package io.github.ratr2.repack.edit

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.block.BlockTemplate
import io.github.ratr2.repack.block.BukkitMaterials
import io.github.ratr2.repack.block.MaterialRules
import io.github.ratr2.repack.config.ConfigMigrator
import io.github.ratr2.repack.model.ModelLibrary
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class BlockEditor(
    private val plugin: Repack,
    private val materials: MaterialRules = BukkitMaterials,
    private val reload: (String) -> String? = { id -> plugin.reloadDefinitions(setOf(id)).firstOrNull { it.startsWith("$id:") } },
) {

    private val idPattern = Regex("[a-z0-9_-]+")

    private fun fileOf(id: String) = File(plugin.blocksFolder, "$id.yml")

    // When each block's file was last changed as far as an open draft knows: a draft saved over a file that changed since would put the old values back, so it is refused.
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun stamp(id: String) = fileOf(id).let { if (it.isFile) it.lastModified() else 0L }

    fun open(id: String): Draft? {
        if (!idPattern.matches(id)) return null
        val file = fileOf(id)
        val yaml = when {
            file.isFile -> YamlConfiguration().apply { load(file) }
            id in plugin.blocks.types -> YamlConfiguration().apply { loadFromString(BlockTemplate.render(id)) }
            else -> return null
        }
        seen[id] = stamp(id)
        return Draft(id, yaml, materials, ::write)
    }

    fun create(id: String, model: String): Result<Draft> {
        if (!idPattern.matches(id)) return Result.failure(IllegalArgumentException("Block ids use only a-z, 0-9, _ and -."))
        if (id in plugin.blocks.types || fileOf(id).exists()) return Result.failure(IllegalArgumentException("A block with the id '$id' already exists."))
        val cleaned = ModelLibrary.cleanId(model)
        if (cleaned !in ModelLibrary.ids(plugin.modelsFolder)) return Result.failure(IllegalArgumentException("There is no model file '$cleaned.bbmodel'."))
        val yaml = YamlConfiguration().apply { loadFromString(BlockTemplate.render(id)) }
        yaml.set("model", cleaned)
        seen[id] = 0L
        return Result.success(Draft(id, yaml, materials, ::write))
    }

    /**
     * Takes a block away for good: its settings file, and its model file when no other block shows that model. Both are moved
     * into `deleted/<time>/` in the plugin's folder instead of being erased. Returns a message when it could not, or null.
     */
    fun delete(id: String): String? {
        if (!idPattern.matches(id) || id !in plugin.blocks.types) return "There is no block '$id'."
        val model = plugin.blocks.definitionOf(id)?.model ?: id
        // A model is also in use when another block shows it as its item, or a custom item does.
        val others = plugin.blocks.definitions.values.any { it.id != id && (it.model == model || it.item.model == model) } ||
            plugin.customItems.definitions.values.any { it.model == model }
        val modelFiles = plugin.modelsFolder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) && ModelLibrary.idOf(f) == model }.orEmpty()
        if (others && id == model && plugin.config.getBoolean("auto-blocks", false)) {
            return "Other blocks show the model '$model', so '$id' would be made again. Delete those first, or turn off auto-blocks."
        }
        return try {
            val trash = File(plugin.dataFolder, "deleted/${System.currentTimeMillis()}-$id").apply { mkdirs() }
            val file = fileOf(id)
            if (file.isFile) Files.move(file.toPath(), File(trash, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (!others) for (modelFile in modelFiles) Files.move(modelFile.toPath(), File(trash, modelFile.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            reload(id)
        } catch (e: java.io.IOException) {
            "Could not delete '$id': ${e.message}"
        }
    }

    private fun write(id: String, yaml: YamlConfiguration): String? {
        val file = fileOf(id)
        if (seen[id] != null && seen[id] != stamp(id)) return "${file.name} changed since you opened it, so saving would put the old values back. Throw away your changes and open the block again."
        return try {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ConfigMigrator.withVersion(yaml, BlockTemplate.version).saveToString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            seen[id] = stamp(id)
            reload(id)
        } catch (e: java.io.IOException) {
            runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
            "Could not write ${file.name}: ${e.message}"
        }
    }
}
