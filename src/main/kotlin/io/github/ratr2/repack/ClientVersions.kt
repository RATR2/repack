package io.github.ratr2.repack

import com.viaversion.viaversion.api.Via
import io.github.ratr2.repack.pack.PackVariant
import org.bukkit.entity.Player
import java.util.UUID

class ClientVersions(plugin: Repack) {

    private val viaLookup: ((UUID) -> Int)? =
        if (plugin.server.pluginManager.isPluginEnabled("ViaVersion")) ViaSupport.lookup() else null

    val hasVia get() = viaLookup != null
    val hasViaBackwards = plugin.server.pluginManager.isPluginEnabled("ViaBackwards")

    fun protocolOf(player: Player): Int {
        val protocol = viaLookup?.invoke(player.uniqueId) ?: return PackVariant.SERVER_PROTOCOL
        return if (protocol > 0) protocol else PackVariant.SERVER_PROTOCOL
    }

    private fun serverProtocol(): Int = PacketEvents.getAPI().serverManager.version.protocolVersion
}

/** Kept apart so ViaVersion's classes are only loaded when the plugin is installed. */
private object ViaSupport {
    fun lookup(): (UUID) -> Int = { uuid -> Via.getAPI().getPlayerVersion(uuid) }
}
