package io.github.ratr2.repack

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class PlayerSettings(private val plugin: Repack) {

    private val file = File(plugin.dataFolder, "players.yml")
    private val yaml = YamlConfiguration()

    init {
        if (file.isFile) {
            try {
                yaml.load(file)
            } catch (e: Exception) {
                plugin.logger.warning("players.yml is not valid YAML, so every player's own settings start over: ${e.message?.lineSequence()?.firstOrNull()}")
            }
        }
    }

    fun bool(player: UUID, key: String): Boolean = yaml.getBoolean("$player.$key", true)

    fun set(player: UUID, key: String, value: Boolean) {
        yaml.set("$player.$key", if (value) null else false)
        if (yaml.getConfigurationSection(player.toString())?.getKeys(false)?.isEmpty() == true) yaml.set(player.toString(), null)
        try {
            yaml.save(file)
        } catch (e: java.io.IOException) {
            plugin.logger.warning("Could not save players.yml: ${e.message}")
        }
    }

    companion object {
        const val NOTICES = "notices"
        const val LOADING_SCREEN = "loading-screen"
        const val PROGRESS_BAR = "progress-bar"
    }
}
