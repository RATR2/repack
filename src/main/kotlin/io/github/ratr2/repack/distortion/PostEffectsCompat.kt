package io.github.ratr2.repack.distortion

import net.kyori.adventure.key.Key
import org.bukkit.entity.Player

/**
 * `Player.postEffects()` only exists in the 26.3+ API. Repack compiles against 1.21.4, so it is reached by reflection
 * and only ever called for players whose pack era uses post effects (which means the server is 26.3 or newer).
 */
internal object PostEffectsCompat {

    private val accessor by lazy { runCatching { Player::class.java.getMethod("postEffects") }.getOrNull() }

    val available get() = accessor != null

    fun add(player: Player, key: Key) = call(player, "add", key)

    fun remove(player: Player, key: Key) = call(player, "remove", key)

    private fun call(player: Player, name: String, key: Key) {
        val effects = accessor?.invoke(player) ?: return
        val method = effects.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == 1 && it.parameterTypes[0].isAssignableFrom(key.javaClass)
        } ?: return
        method.isAccessible = true
        method.invoke(effects, key)
    }
}
