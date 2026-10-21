package io.github.ratr2.repack

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player

class BuildProgress(private val plugin: Repack) {

    private var bar: BossBar? = null
    private var viewers = emptyList<Player>()

    fun start() {
        if (!plugin.config.getBoolean("workflow.progress-bar", true)) return
        val created = BossBar.bossBar(Component.text("Repack: starting the build"), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS)
        viewers = plugin.server.onlinePlayers.filter { it.hasPermission(RepackCommand.ADMIN) && plugin.players.bool(it.uniqueId, PlayerSettings.PROGRESS_BAR) }
        viewers.forEach { it.showBossBar(created) }
        bar = created
    }

    /** Moves the bar; safe to call from the build thread. */
    fun update(fraction: Float, text: String) {
        val current = bar ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        current.progress(clamped)
        current.name(Component.text("Repack: $text (${(clamped * 100).toInt()}%)"))
    }

    /** Fills the bar in green or red, leaves it up for two seconds and takes it away. Call on the main thread. */
    fun finish(success: Boolean, text: String) {
        val current = bar ?: return
        bar = null
        current.progress(1f)
        current.color(if (success) BossBar.Color.GREEN else BossBar.Color.RED)
        current.name(Component.text("Repack: $text"))
        val shown = viewers
        viewers = emptyList()
        plugin.server.scheduler.runTaskLater(plugin, Runnable { shown.forEach { it.hideBossBar(current) } }, 40L)
    }
}
