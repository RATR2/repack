package io.github.ratr2.repack.distortion

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.pack.PackSender
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
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
        val marker = Marker(SpigotReflectionUtil.generateEntityId(), shake)
        active[player.uniqueId] = marker
        spawn(player, marker)
        if (era.usesPostEffects) io.github.ratr2.repack.distortion.PostEffectsCompat.add(player, POST_EFFECT)
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
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerDestroyEntities(marker.entityId))
        if (pack.loadedEra(player)?.usesPostEffects == true) io.github.ratr2.repack.distortion.PostEffectsCompat.remove(player, POST_EFFECT)
    }

    private fun tick() {
        if (active.isEmpty()) return
        val now = Bukkit.getCurrentTick()
        val players = PacketEvents.getAPI().playerManager
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
            players.sendPacket(player, WrapperPlayServerEntityTeleport(marker.entityId, locationOf(player), false))
            if (strength != marker.lastStrength) {
                marker.lastStrength = strength
                players.sendPacket(player, WrapperPlayServerEntityMetadata(marker.entityId, listOf(EntityData(SCALE, EntityDataTypes.VECTOR3F, scaleOf(strength)))))
            }
        }
    }

    /** Half a block under the eyes: the culling box then holds the camera, so the display is never left out of the frame. */
    private fun locationOf(player: Player): PacketLocation {
        val eyes = player.eyeLocation
        return PacketLocation(eyes.x, eyes.y - 0.5, eyes.z, 0f, 0f)
    }

    private fun scaleOf(strength: Double) = Vector3f((strength * SCALE_UNIT).toFloat(), SCALE_UNIT, 1f)

    private fun spawn(player: Player, marker: Marker) {
        val shake = marker.shake
        val color = TextColor.color((shake.rate * 255).toInt(), (shake.balance * 255).toInt(), (shake.roll * 255).toInt())
        val data = listOf(
            EntityData(FLAGS, EntityDataTypes.BYTE, 0),
            EntityData(INTERPOLATION_START, EntityDataTypes.INT, 0),
            EntityData(TRANSFORM_DURATION, EntityDataTypes.INT, 0),
            EntityData(TELEPORT_DURATION, EntityDataTypes.INT, 1),
            // Three blocks ahead of the camera, whichever way it looks: a camera facing display's own z axis points at the camera.
            EntityData(TRANSLATION, EntityDataTypes.VECTOR3F, Vector3f(0f, 0f, -3f)),
            EntityData(SCALE, EntityDataTypes.VECTOR3F, scaleOf(shake.at(0))),
            EntityData(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
            EntityData(BRIGHTNESS, EntityDataTypes.INT, 0),
            EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, 4f),
            EntityData(WIDTH, EntityDataTypes.FLOAT, BOX),
            EntityData(HEIGHT, EntityDataTypes.FLOAT, BOX),
            EntityData(TEXT, EntityDataTypes.ADV_COMPONENT, Component.text(GLYPH, color)),
            EntityData(BACKGROUND, EntityDataTypes.INT, 0),
            EntityData(TEXT_OPACITY, EntityDataTypes.BYTE, OPACITY),
            EntityData(TEXT_FLAGS, EntityDataTypes.BYTE, 0),
        )
        val players = PacketEvents.getAPI().playerManager
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(player, WrapperPlayServerSpawnEntity(marker.entityId, UUID.randomUUID(), EntityTypes.TEXT_DISPLAY, locationOf(player), 0f, 0, Vector3d.zero()))
        players.sendPacket(player, WrapperPlayServerEntityMetadata(marker.entityId, data))
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    fun onPackLost(player: Player) {
        stop(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        active.remove(event.player.uniqueId)
        toldAboutFabulous.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        active.remove(event.player.uniqueId)?.let {
            if (pack.loadedEra(event.player)?.usesPostEffects == true) PostEffectsCompat.remove(event.player, POST_EFFECT)
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        active.remove(event.player.uniqueId)?.let {
            if (pack.loadedEra(event.player)?.usesPostEffects == true) PostEffectsCompat.remove(event.player, POST_EFFECT)
        }
    }

    private companion object {
        val POST_EFFECT: Key = Key.key("distortion", "shake")

        // The marker glyph and the text_opacity the pack's text shader takes for a shake (the ripples use 123).
        const val GLYPH = "􃗏"
        const val OPACITY: Byte = 122
        const val SCALE_UNIT = 0.01f
        const val BOX = 8f
        const val BILLBOARD_CENTER: Byte = 3

        const val FLAGS = 0
        const val INTERPOLATION_START = 8
        const val TRANSFORM_DURATION = 9
        const val TELEPORT_DURATION = 10
        const val TRANSLATION = 11
        const val SCALE = 12
        const val BILLBOARD = 15
        const val BRIGHTNESS = 16
        const val VIEW_RANGE = 17
        const val WIDTH = 20
        const val HEIGHT = 21
        const val TEXT = 23
        const val BACKGROUND = 25
        const val TEXT_OPACITY = 26
        const val TEXT_FLAGS = 27
    }
}
