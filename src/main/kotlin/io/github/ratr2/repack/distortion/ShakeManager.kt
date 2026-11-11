package io.github.ratr2.repack.distortion

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.pack.PackSender
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.UUID
import kotlin.math.pow

/**
 * Shakes a player's screen with a text display only they see, kept in front of their eyes. The pack's text shader turns it into
 * data pixels in the corner of the frame (strength from the glyph size, the rest from the text color) and the shake pass moves,
 * turns and zooms the picture from them. Needs 1.21.6 or newer; before 26.3 it needs Fabulous! graphics, from 26.3 the pass is a
 * post effect added only while the player shakes.
 */
class ShakeManager(private val plugin: Repack, private val pack: PackSender) : Listener {

    class Shake(val start: Int, val ticks: Int, val strength: Double, val rate: Double, val balance: Double, val roll: Double) {
        fun at(elapsed: Int): Double = if (elapsed >= ticks) 0.0 else strength * (1.0 - elapsed.toDouble() / ticks).pow(2)
    }

    private class Marker(val entityId: Int, var shake: Shake, var lastStrength: Double = -1.0)

    private val active = HashMap<UUID, Marker>()
    private val toldAboutFabulous = HashSet<UUID>()

    init {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tick), 1L, 1L)
    }

    fun canShake(player: Player): Boolean = pack.loadedEra(player)?.shakes == true

    fun start(player: Player, strength: Double, ticks: Int, rate: Double = 0.5, balance: Double = 0.5, roll: Double = 0.3): Boolean {
        require(strength.isFinite() && ticks > 0) { "strength must be a number and ticks above 0" }
        val era = pack.loadedEra(player) ?: return false
        if (!era.shakes) return false
        val now = Bukkit.getCurrentTick()
        val shake = Shake(now, ticks, strength.coerceIn(0.0, 1.0), rate.coerceIn(0.0, 1.0), balance.coerceIn(0.0, 1.0), roll.coerceIn(0.0, 1.0))
        val running = active[player.uniqueId]
        if (running != null) {
            if (shake.strength >= running.shake.at(now - running.shake.start)) {
                running.shake = shake
                running.lastStrength = -1.0
            }
            return true
        }
        if (!era.usesPostEffects && toldAboutFabulous.add(player.uniqueId)) pack.fabulousNotice(player)
        val marker = Marker(ScreenMarker.newId(), shake)
        active[player.uniqueId] = marker
        spawn(player, marker)
        if (era.usesPostEffects) ScreenMarker.acquirePost(player)
        return true
    }

    fun stop(player: Player) {
        val marker = active.remove(player.uniqueId) ?: return
        finish(player, marker)
    }

    fun clear() {
        for ((id, marker) in active.toMap()) Bukkit.getPlayer(id)?.let { finish(it, marker) }
        active.clear()
    }

    val activeCount: Int get() = active.size

    private fun finish(player: Player, marker: Marker) {
        ScreenMarker.destroy(player, marker.entityId)
        if (pack.loadedEra(player)?.usesPostEffects == true) ScreenMarker.releasePost(player)
    }

    private fun tick() {
        if (active.isEmpty()) return
        val now = Bukkit.getCurrentTick()
        for ((id, marker) in active.toMap()) {
            val player = Bukkit.getPlayer(id)
            if (player == null || !player.isOnline) {
                active.remove(id)
                continue
            }
            val strength = marker.shake.at(now - marker.shake.start)
            if (strength <= 0.0) {
                active.remove(id)
                finish(player, marker)
                continue
            }
            ScreenMarker.follow(player, marker.entityId)
            if (strength != marker.lastStrength) {
                marker.lastStrength = strength
                ScreenMarker.setStrength(player, marker.entityId, strength)
            }
        }
    }

    private fun spawn(player: Player, marker: Marker) {
        val shake = marker.shake
        val color = TextColor.color((shake.rate * 255).toInt(), (shake.balance * 255).toInt(), (shake.roll * 255).toInt())
        ScreenMarker.spawn(player, marker.entityId, ScreenMarker.SHAKE_OPACITY, ScreenMarker.SHAKE_SLOT, color, shake.at(0))
    }

    fun onPackLost(player: Player) {
        stop(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        active.remove(event.player.uniqueId)
        ScreenMarker.forgetPostQuietly(event.player)
        toldAboutFabulous.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        active.remove(event.player.uniqueId)?.let {
            if (pack.loadedEra(event.player)?.usesPostEffects == true) ScreenMarker.releasePost(event.player)
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        active.remove(event.player.uniqueId)?.let {
            if (pack.loadedEra(event.player)?.usesPostEffects == true) ScreenMarker.releasePost(event.player)
        }
    }
}
