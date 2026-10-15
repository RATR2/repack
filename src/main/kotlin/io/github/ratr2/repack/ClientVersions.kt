package io.github.ratr2.repack

import com.github.retrooper.packetevents.PacketEvents
import com.viaversion.viaversion.api.Via
import org.bukkit.entity.Player
import java.util.UUID

/** Looks up the protocol version a player connected with: the server's own, unless ViaVersion translates it. */
class ClientVersions(plugin: Repack) {

    private val viaLookup: ((UUID) -> Int)? =
        if (plugin.server.pluginManager.isPluginEnabled("ViaVersion")) ViaSupport.lookup() else null

    val hasVia get() = viaLookup != null
    val hasViaBackwards = plugin.server.pluginManager.isPluginEnabled("ViaBackwards")

    fun protocolOf(player: Player): Int {
        val protocol = viaLookup?.invoke(player.uniqueId) ?: return serverProtocol()
        return if (protocol > 0) protocol else serverProtocol()
    }

    /** The protocol of the server's own version, which is what a client without translation connects with. */
    private fun serverProtocol(): Int = PacketEvents.getAPI().serverManager.version.protocolVersion
}

/** Kept apart so ViaVersion's classes are only loaded when the plugin is installed. */
private object ViaSupport {
    fun lookup(): (UUID) -> Int = { uuid -> Via.getAPI().getPlayerVersion(uuid) }
}
