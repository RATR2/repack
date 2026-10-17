package io.github.ratr2.repack.config

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.security.MessageDigest

/**
 * The version of a settings file is a short hash of the text the plugin ships for it, so editing that text changes the version by
 * itself and nobody has to bump a number. The version lines are left out of the hash.
 */
object ConfigVersions {

    private val VERSION_LINES = Regex("# DO NOT EDIT\\. Version:.*|config-version:.*")

    fun of(text: String): String {
        val body = text.lines().map { it.trimEnd() }.filterNot { VERSION_LINES.matches(it) }.joinToString("\n").trim()
        // The letter keeps YAML from reading an all-digit hash as a number.
        return "v" + MessageDigest.getInstance("SHA-1").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }
}

/**
 * A change the new defaults cannot express, such as renaming or removing an option. It runs every time a file is migrated, so
 * write it to do nothing when the file is already in the new shape.
 */
class ConfigMigration(val apply: (YamlConfiguration) -> Unit)

object ConfigMigrator {

    const val KEY = "config-version"

    fun note(version: String) = "DO NOT EDIT. Version: $version"

    fun migrate(file: File, current: String, migrations: List<ConfigMigration> = emptyList(), defaults: YamlConfiguration? = null, backups: File? = null): String? {
        if (!file.isFile) return null
        val yaml = YamlConfiguration()
        try {
            yaml.load(file)
        } catch (e: Exception) {
            return "${file.name}: not valid YAML, so it was not migrated (${e.message?.lineSequence()?.firstOrNull()})."
        }
        val found = yaml.getString(KEY)
        if (found == current) return null

        val before = snapshot(yaml)
        migrations.forEach { it.apply(yaml) }
        if (defaults != null) addMissing(yaml, defaults)
        val changed = snapshot(yaml) != before
        val backup = File(file.parentFile, "${file.name}.bak-${(found ?: "unversioned").replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)}")
        if (changed && !backup.exists()) file.copyTo(backup)
        withVersion(yaml, current).save(file)
        return if (changed) "${file.name}: updated to version $current (the old file is kept as ${backup.name})." else null
    }

    private fun snapshot(yaml: YamlConfiguration) = yaml.getValues(true).filterKeys { it != KEY }.mapValues { it.value.toString() }

    fun addMissing(yaml: YamlConfiguration, defaults: YamlConfiguration) {
        val missing = defaults.getKeys(true).filter { it != KEY && !yaml.contains(it, true) }
        for (key in missing) if (!defaults.isConfigurationSection(key)) yaml.set(key, defaults.get(key))
        for (key in missing) {
            yaml.setComments(key, defaults.getComments(key))
            yaml.setInlineComments(key, defaults.getInlineComments(key))
        }
    }

    fun withVersion(yaml: YamlConfiguration, version: String): YamlConfiguration {
        val out = YamlConfiguration()
        out.set(KEY, version)
        out.setComments(KEY, listOf(note(version)))
        val keys = yaml.getKeys(true).filter { it != KEY }
        for (key in keys) {
            if (!yaml.isConfigurationSection(key)) out.set(key, yaml.get(key))
            else if (yaml.getConfigurationSection(key)!!.getKeys(false).isEmpty()) out.createSection(key)
        }
        for (key in keys) {
            out.setComments(key, yaml.getComments(key))
            out.setInlineComments(key, yaml.getInlineComments(key))
        }
        return out
    }
}
