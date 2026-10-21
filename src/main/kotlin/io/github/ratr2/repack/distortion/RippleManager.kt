package io.github.ratr2.repack.distortion

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.pack.PackSender
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * Spawns the marker text displays the resource pack's text shader turns into ripples.
 * Must match the text shaders (text.vsh / text.fsh, rendertype_text.* before 26.2) in every pack under packs/ in the jar.
 */
class RippleManager(private val plugin: Repack, private val pack: PackSender) : Listener {

    companion object {
        const val SLOTS = 256
        private const val GLYPH = "\uDBCD\uDDCF"
        private const val OPACITY: Byte = 123
        private const val SCALE_UNIT = 0.01f
        private const val START_DELAY_TICKS = 3L
        // Start slightly above 0 so the glyph is never a zero-size quad.
        private const val MIN_RADIUS = 0.05f
        private val POST_EFFECT = Key.key("distortion", "ripple")
        private val DEBUG_EFFECT = Key.key("distortion", "ripple_debug")

        /** The biggest ripple radius in blocks; larger ones are not useful and could overflow the float the client takes. */
        const val MAX_RADIUS = 1024.0

        fun validRadius(radius: Double) = radius.isFinite() && radius > 0 && radius <= MAX_RADIUS
    }

    private val slots = arrayOfNulls<TextDisplay>(SLOTS)
    private val debugging = HashSet<UUID>()

    val activeCount get() = slots.count { it != null }

    fun spawn(location: Location, rgb: Int, radius: Double, ticks: Int): Boolean {
        require(validRadius(radius) && ticks > 0) { "radius must be between 0 and $MAX_RADIUS blocks and ticks must be positive" }
        val world = location.world ?: return false
        val slot = slots.indexOfFirst { it == null }
        if (slot < 0) return false
        val display = world.spawn(location.clone().apply { yaw = 0f; pitch = 0f }, TextDisplay::class.java) {
            it.isPersistent = false
            it.isInvisible = true
            it.text(Component.text(GLYPH, TextColor.color(rgb and 0xFFFFFF)))
            it.textOpacity = OPACITY
            it.backgroundColor = Color.fromARGB(0, 0, 0, 0)
            it.isShadowed = false
            it.isSeeThrough = false
            it.billboard = Display.Billboard.FIXED
            // The pack reads the slot as block + 16 * sky.
            it.brightness = Display.Brightness(slot % 16, slot / 16)
            it.transformation = transformation(strength = 1f, radius = MIN_RADIUS)
        }
        slots[slot] = display
        // Visible by default so pack users get plain entity tracking (hidden-by-default plus showEntity kept respawning client-
        // side as players moved); only players without the pack get it hidden.
        plugin.server.onlinePlayers.forEach {
            if (pack.hasLoaded(it)) applyEffect(it) else it.hideEntity(plugin, display)
        }

        // One client-side interpolation to the end state, like /data merge on a summoned marker; started after a short
        // delay so clients have spawned the start state first, or they'd snap straight to the end.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!display.isValid || slots[slot] !== display) return@Runnable
            display.interpolationDelay = 0
            display.interpolationDuration = ticks
            display.transformation = transformation(strength = 0f, radius = radius.toFloat())
        }, START_DELAY_TICKS)
        plugin.server.scheduler.runTaskLater(plugin, Runnable { remove(slot, display) }, START_DELAY_TICKS + ticks + 1L)
        return true
    }

    fun clear() {
        for (slot in slots.indices) slots[slot]?.let { remove(slot, it) }
        pack.loadedPlayers().filter(pack::usesPostEffects).forEach { PostEffectsCompat.remove(it, effectFor(it)) }
    }

    fun onPackLost(player: Player, usedPostEffects: Boolean) {
        slots.filterNotNull().forEach { player.hideEntity(plugin, it) }
        if (usedPostEffects) {
            debugging.remove(player.uniqueId)
            PostEffectsCompat.remove(player, POST_EFFECT)
            PostEffectsCompat.remove(player, DEBUG_EFFECT)
        }
    }

    fun onPackLoaded(player: Player) {
        slots.filterNotNull().forEach { player.showEntity(plugin, it) }
        applyEffect(player)
    }

    /**
     * Older clients (1.21.x to 26.2) have no server driven post effect packet; their pack carries the ripple passes in
     * the Fabulous graphics transparency effect, so there is nothing to add for them.
     */
    private fun applyEffect(player: Player) {
        if (pack.usesPostEffects(player)) PostEffectsCompat.add(player, effectFor(player))
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        slots.filterNotNull().forEach { event.player.hideEntity(plugin, it) }
    }

    fun isDebugging(player: Player) = player.uniqueId in debugging

    fun setDebug(player: Player, enabled: Boolean): Boolean {
        if (!pack.supportsPostEffects(player)) return false
        PostEffectsCompat.remove(player, effectFor(player))
        if (enabled) debugging.add(player.uniqueId) else debugging.remove(player.uniqueId)
        if (pack.hasLoaded(player)) PostEffectsCompat.add(player, effectFor(player))
        return true
    }

    private fun effectFor(player: Player) = if (isDebugging(player)) DEBUG_EFFECT else POST_EFFECT

    private fun remove(slot: Int, display: TextDisplay) {
        if (slots[slot] !== display) return
        slots[slot] = null
        display.remove()
    }

    private fun transformation(strength: Float, radius: Float) = Transformation(
        Vector3f(),
        Quaternionf(),
        Vector3f(strength * SCALE_UNIT, radius * SCALE_UNIT, SCALE_UNIT),
        Quaternionf(),
    )

    // The client may drop post effects on respawn or dimension change, so re-apply.
    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) = reapply(event.player)

    @EventHandler
    fun onChangedWorld(event: PlayerChangedWorldEvent) = reapply(event.player)

    private fun reapply(player: Player) {
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (player.isOnline && pack.hasLoaded(player)) applyEffect(player)
        }, 1L)
    }
}
