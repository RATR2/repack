package io.github.ratr2.repack.pack

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * A player has joined as far as Repack is concerned: their client loaded the resource pack, or it refused or failed to, or took too long, or they are not sent a
 * pack at all. Bukkit's join event fires the moment they connect, before the pack; scripts that need the pack (a GUI, a screen effect, a custom block) run on
 * this instead. It fires once per join, on the main thread, after login limbo has let them in and their join message has been sent.
 */
class RepackJoinEvent(val player: Player, val reason: Reason) : Event() {

    enum class Reason(val text: String) {
        /** The client loaded the pack. */
        LOADED("loaded"),

        /** The client declined the pack, could not download it, or failed to load it. */
        FAILED("failed"),

        /** Neither happened within `limbo.timeout-seconds`. */
        TIMEOUT("timeout"),

        /** The player is not sent a pack (a client that cannot take it, or `resource-pack.send: false`), so there was nothing to wait for. */
        NOT_SENT("not sent"),
    }

    override fun getHandlers() = handlerList

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
