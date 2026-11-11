package io.github.ratr2.repack.distortion

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.pack.PackSender
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Impact frames: for a few ticks the player's whole screen is drawn as a hard black and white picture, smeared outward in jagged spikes from the nearest of a few
 * focus points, with outlines where one shape stands in front of another, and each focus point draws a shape of its own over it: a burst of wedges, an explosion,
 * lightning or an ink splotch. Like the frame an anime holds on a hit.
 *
 * Works like the ripples: a focus point is a text display, only that player sees it, at a point in the world, which the pack's shaders read. So it needs the
 * pack, a 1.21.6 or newer client, and Fabulous! graphics before 26.3, and a focus point has to be in front of the camera.
 */
class ImpactManager(private val plugin: Repack, private val pack: PackSender) : Listener {

    /**
     * The shape a focus point draws. The order is what the shader reads (the sky light of the marker). The first four are round; the last four have a direction:
     * a slash, speed lines, shatter cracks and a stretched blast.
     */
    enum class Type(val id: String, val directional: Boolean) {
        RADIAL("radial", false), EXPLOSION("explosion", false), LIGHTNING("lightning", false), SPLOTCH("splotch", false),
        SLASH("slash", true), LINES("lines", true), SHATTER("shatter", true), STRETCHED("stretched", true);

        companion object {
            fun of(name: String?): Type? = entries.firstOrNull { it.id.equals(name?.trim(), ignoreCase = true) }
        }
    }

    /**
     * A point in the world, the shape drawn around it and how big it is in blocks (0.1 to 64). A directional type follows [direction], a vector in the world
     * (the player sees it as the angle it makes on their screen), or [screenAngle], an angle on the screen in degrees (0 is to the right, 90 up); without either it
     * points to the right.
     */
    class Focus(val location: Location, val type: Type, val size: Double, val direction: Vector? = null, val screenAngle: Double? = null)

    private class Active(var end: Int, val rgb: Int, val markers: MutableList<Int> = ArrayList())

    private val active = HashMap<UUID, Active>()
    private val toldAboutFabulous = HashSet<UUID>()

    init {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tick), 1L, 1L)
    }

    fun canShow(player: Player): Boolean = pack.loadedEra(player)?.shakes == true

    val activeCount: Int get() = active.size

    /**
     * Starts an impact frame that lasts [ticks] (1 to 200) with [rgb] as the light tone (the dark tone is black) and up to [MAX_FOCI] focus points. Focus points in
     * another world than the player's are left out. False when the player's client cannot show it or no focus point is usable. One at a time per player: a new one
     * replaces the running one.
     */
    fun start(player: Player, ticks: Int, rgb: Int, foci: List<Focus>): Boolean {
        require(ticks > 0) { "ticks must be above 0" }
        val era = pack.loadedEra(player) ?: return false
        if (!era.shakes || foci.none { usable(player, it) }) return false
        stop(player)
        if (!era.usesPostEffects && toldAboutFabulous.add(player.uniqueId)) pack.fabulousNotice(player)
        val impact = Active(Bukkit.getCurrentTick() + ticks.coerceIn(1, MAX_TICKS), rgb and 0xFFFFFF)
        active[player.uniqueId] = impact
        addMarkers(player, impact, foci)
        if (era.usesPostEffects) ScreenMarker.acquirePost(player)
        return true
    }

    /** Adds focus points to the impact frame the player is in, up to [MAX_FOCI] in all. False when there is none running or nothing was added. */
    fun add(player: Player, foci: List<Focus>): Boolean {
        val impact = active[player.uniqueId] ?: return false
        return addMarkers(player, impact, foci) > 0
    }

    private fun usable(player: Player, focus: Focus) =
        focus.location.world == player.world && focus.size.isFinite() && focus.size >= MIN_SIZE && focus.location.x.isFinite() && focus.location.y.isFinite() && focus.location.z.isFinite()

    private fun addMarkers(player: Player, impact: Active, foci: List<Focus>): Int {
        var added = 0
        for (focus in foci) {
            if (impact.markers.size >= MAX_FOCI) break
            if (!usable(player, focus)) continue
            val id = ScreenMarker.newId()
            // Slot 0 is the shake's row; the focus points take 1 to 15. The sky light is the type, the width the direction and the height the size.
            val angle = angleOn(player, focus)
            ScreenMarker.spawnAt(
                player, id, focus.location, ScreenMarker.IMPACT_OPACITY, impact.markers.size + 1, focus.type.ordinal, TextColor.color(impact.rgb),
                ((ANGLE_MIN + (1.0 - ANGLE_MIN) * angle) * WIDTH_UNIT).toFloat(), (focus.size.coerceAtMost(MAX_SIZE) * SIZE_UNIT).toFloat(),
            )
            impact.markers += id
            added++
        }
        return added
    }

    /** A direction as a turn of the screen from 0 to 1 (0 is to the right, a quarter is up): a world vector is projected on what the player's camera sees. */
    private fun angleOn(player: Player, focus: Focus): Double {
        focus.screenAngle?.let { return ((it % 360.0 + 360.0) % 360.0) / 360.0 }
        val direction = focus.direction ?: return 0.0
        val forward = player.eyeLocation.direction
        val right = forward.clone().crossProduct(Vector(0, 1, 0))
        if (right.lengthSquared() < 1.0e-6) return 0.0
        right.normalize()
        val up = right.clone().crossProduct(forward).normalize()
        val turn = Math.atan2(direction.dot(up), direction.dot(right)) / (2 * Math.PI)
        return (turn + 1.0) % 1.0
    }

    fun stop(player: Player) {
        val impact = active.remove(player.uniqueId) ?: return
        finish(player, impact)
    }

    fun clear() {
        for ((id, impact) in active.toMap()) Bukkit.getPlayer(id)?.let { finish(it, impact) }
        active.clear()
    }

    private fun finish(player: Player, impact: Active) {
        impact.markers.forEach { ScreenMarker.destroy(player, it) }
        if (pack.loadedEra(player)?.usesPostEffects == true) ScreenMarker.releasePost(player)
    }

    private fun tick() {
        if (active.isEmpty()) return
        val now = Bukkit.getCurrentTick()
        for ((id, impact) in active.toMap()) {
            val player = Bukkit.getPlayer(id)
            if (player == null || !player.isOnline) {
                active.remove(id)
                continue
            }
            if (now >= impact.end) {
                active.remove(id)
                finish(player, impact)
            }
        }
    }

    fun onPackLost(player: Player) {
        stop(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        active.remove(event.player.uniqueId)
        toldAboutFabulous.remove(event.player.uniqueId)
        ScreenMarker.forgetPostQuietly(event.player)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = drop(event.player)

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) = drop(event.player)

    /** The client drops entities on a world change or respawn, so the markers are already gone; only the post effect is left to release. */
    private fun drop(player: Player) {
        if (active.remove(player.uniqueId) != null && pack.loadedEra(player)?.usesPostEffects == true) ScreenMarker.releasePost(player)
    }

    companion object {
        const val MAX_TICKS = 200

        /** Focus points at once for one player: the shake rows 1 to 15. Must match IMPACT_MAX_FOCI in the post shaders. */
        const val MAX_FOCI = 15

        const val MIN_SIZE = 0.1
        const val MAX_SIZE = 64.0

        /** The glyph scale of a marker at its biggest: the width for the direction, the height for the size. Must match IMPACT_WIDTH_UNIT and IMPACT_SIZE_UNIT in the text shader. */
        const val WIDTH_UNIT = 1.0
        const val SIZE_UNIT = 0.1

        /** The width is ANGLE_MIN to 1 of the unit, so a direction of 0 is not a glyph with no width. Must match the 0.05 in the text shader. */
        const val ANGLE_MIN = 0.05
    }
}
