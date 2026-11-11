package io.github.ratr2.repack.distortion

import io.github.ratr2.repack.RepackAPI
import org.bukkit.Location

/**
 * The ripple effects, callable on the main thread. Also reachable as `RepackAPI.ripple`, `RepackAPI.activeRipples` and `RepackAPI.maxRipples`.
 *
 * ```java
 * boolean ok = DistortionAPI.spawn(location, 0x55CCFF, 8.0, 40);
 * ```
 */
object DistortionAPI {

    @Volatile
    internal var manager: RippleManager? = null

    @JvmStatic
    fun spawn(location: Location, rgb: Int, radius: Double, ticks: Int): Boolean =
        RepackAPI.onMain(false) { manager?.spawn(location, rgb, radius, ticks) ?: false }

    @JvmStatic
    fun activeRipples(): Int = manager?.activeCount ?: 0

    @JvmStatic
    fun maxRipples(): Int = RippleManager.SLOTS

    @Volatile
    internal var shakes: ShakeManager? = null

    @JvmStatic
    fun shake(player: org.bukkit.entity.Player, strength: Double, ticks: Int): Boolean = shake(player, strength, ticks, 0.5, 0.5, 0.3)

    @JvmStatic
    fun shake(player: org.bukkit.entity.Player, strength: Double, ticks: Int, rate: Double, balance: Double, roll: Double): Boolean =
        RepackAPI.onMain(false) { shakes?.start(player, strength, ticks, rate, balance, roll) ?: false }

    @JvmStatic
    fun stopShake(player: org.bukkit.entity.Player) = RepackAPI.onMain(Unit) { shakes?.stop(player) ?: Unit }

    @Volatile
    internal var impacts: ImpactManager? = null

    /**
     * A focus point of an impact frame: a point in the world, the [type] drawn around it (radial, explosion, lightning, splotch, slash, lines, shatter or
     * stretched) and its [size] in blocks. The last four follow [direction] (a vector in the world) or [screenAngle] (degrees on the screen, 0 to the right).
     */
    class ImpactFocus @JvmOverloads constructor(
        val location: org.bukkit.Location, val type: String, val size: Double,
        val direction: org.bukkit.util.Vector? = null, val screenAngle: Double? = null,
    )

    private fun foci(list: List<ImpactFocus>): List<ImpactManager.Focus>? {
        val converted = list.map { ImpactManager.Type.of(it.type)?.let { type -> ImpactManager.Focus(it.location, type, it.size, it.direction, it.screenAngle) } }
        return if (converted.any { it == null }) null else converted.filterNotNull()
    }

    /** One focus point, with white as the light tone. */
    @JvmStatic
    fun impact(player: org.bukkit.entity.Player, ticks: Int, location: org.bukkit.Location, type: String, size: Double): Boolean =
        impact(player, ticks, 0xFFFFFF, listOf(ImpactFocus(location, type, size)))

    @JvmStatic
    fun impact(player: org.bukkit.entity.Player, ticks: Int, rgb: Int, location: org.bukkit.Location, type: String, size: Double): Boolean =
        impact(player, ticks, rgb, listOf(ImpactFocus(location, type, size)))

    /** Any number of focus points (up to 15). False when a type is unknown, the client cannot show it, or no focus point is in the player's world. */
    @JvmStatic
    fun impact(player: org.bukkit.entity.Player, ticks: Int, rgb: Int, foci: List<ImpactFocus>): Boolean {
        val converted = foci(foci) ?: return false
        return RepackAPI.onMain(false) { impacts?.start(player, ticks, rgb, converted) ?: false }
    }

    /** Shows resolved focus points; what [ImpactScene.play] uses. */
    internal fun playFoci(player: org.bukkit.entity.Player, ticks: Int, rgb: Int, foci: List<ImpactManager.Focus>): Boolean =
        RepackAPI.onMain(false) { impacts?.start(player, ticks, rgb, foci) ?: false }

    /** Adds a focus point to the impact frame the player is in. False when there is none running. */
    @JvmStatic
    fun addImpactFocus(player: org.bukkit.entity.Player, location: org.bukkit.Location, type: String, size: Double): Boolean = addImpactFocus(player, location, type, size, null)

    @JvmStatic
    fun addImpactFocus(player: org.bukkit.entity.Player, location: org.bukkit.Location, type: String, size: Double, screenAngle: Double?): Boolean {
        val converted = foci(listOf(ImpactFocus(location, type, size, null, screenAngle))) ?: return false
        return RepackAPI.onMain(false) { impacts?.add(player, converted) ?: false }
    }

    @JvmStatic
    fun stopImpact(player: org.bukkit.entity.Player) = RepackAPI.onMain(Unit) { impacts?.stop(player) ?: Unit }

    /** Whether this player's client has the pack and can show screen shake and impact frames. */
    @JvmStatic
    fun canShowScreenEffects(player: org.bukkit.entity.Player): Boolean = RepackAPI.onMain(false) { impacts?.canShow(player) ?: false }
}
