package io.github.ratr2.repack.edit

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.block.BukkitMaterials
import io.github.ratr2.repack.config.ConfigMigrator
import io.github.ratr2.repack.item.ItemTemplate
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ItemEditor(private val plugin: Repack) {

    private val idPattern = Regex("[a-z0-9_-]+")

    private fun fileOf(id: String) = File(plugin.itemsFolder, "$id.yml")

    // When each item's file was last changed as far as an open draft knows: saving a draft over a file that changed since (a script, a hand edit, another admin)
    // would put the old values back without a word, so it is refused instead.
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun stamp(id: String) = fileOf(id).let { if (it.isFile) it.lastModified() else 0L }

    fun open(id: String): ItemDraft? {
        if (!idPattern.matches(id) || id !in plugin.customItems.ids) return null
        val file = fileOf(id)
        val yaml = if (file.isFile) YamlConfiguration().apply { load(file) } else YamlConfiguration().apply { loadFromString(ItemTemplate.render(id)) }
        seen[id] = stamp(id)
        return ItemDraft(id, yaml, ::write)
    }

    fun create(id: String): Result<ItemDraft> {
        if (!idPattern.matches(id)) return Result.failure(IllegalArgumentException("Item ids use only a-z, 0-9, _ and -."))
        if (id in plugin.customItems.ids || fileOf(id).exists()) return Result.failure(IllegalArgumentException("An item with the id '$id' already exists."))
        seen[id] = 0L
        return Result.success(ItemDraft(id, YamlConfiguration().apply { loadFromString(ItemTemplate.render(id)) }, ::write))
    }

    private fun write(id: String, yaml: YamlConfiguration): String? {
        val file = fileOf(id)
        if (seen[id] != null && seen[id] != stamp(id)) return "${file.name} changed since you opened it, so saving would put the old values back. Throw away your changes and open the item again."
        return try {
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ConfigMigrator.withVersion(yaml, ItemTemplate.version).saveToString())
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            seen[id] = stamp(id)
            plugin.reloadItems(clearRuntime = false).firstOrNull { it.startsWith("item $id:") }
        } catch (e: java.io.IOException) {
            "Could not write ${file.name}: ${e.message}"
        }
    }

    fun delete(id: String): String? {
        if (!idPattern.matches(id) || id !in plugin.customItems.ids) return "There is no item '$id'."
        return try {
            val file = fileOf(id)
            if (file.isFile) {
                val trash = File(plugin.dataFolder, "deleted/${System.currentTimeMillis()}-item-$id").apply { mkdirs() }
                Files.move(file.toPath(), File(trash, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            plugin.reloadItems(clearRuntime = false)
            null
        } catch (e: java.io.IOException) {
            "Could not delete '$id': ${e.message}"
        }
    }
}
