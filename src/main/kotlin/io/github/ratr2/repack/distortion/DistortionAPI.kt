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
}
