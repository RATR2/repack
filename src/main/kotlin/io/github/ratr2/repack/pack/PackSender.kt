package io.github.ratr2.repack.pack

import io.github.ratr2.repack.Repack
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID

/**
 * Sends the published pack to players. It has its own pack id, so a client keeps it next to other server packs (such as
 * Distortion's) and replacing it never touches them.
 */
class PackSender(private val plugin: Repack, private val store: PackStore, private val host: PackHost) : Listener {

    private val id: UUID = UUID.nameUUIDFromBytes("repack-pack".toByteArray())

    /** Sends the published pack to [players]; returns how many were sent one, 0 when nothing is published. */
    fun send(players: Collection<Player>): Int {
        val pack = store.published ?: return 0
        val config = plugin.config
        val request = ResourcePackRequest.resourcePackRequest()
            .packs(ResourcePackInfo.resourcePackInfo(id, host.urlOf(pack), pack.sha1))
            .required(config.getBoolean("resource-pack.required", false))
            .prompt(Component.text(config.getString("resource-pack.prompt").orEmpty()))
            .build()
        players.forEach { it.sendResourcePacks(request) }
        return players.size
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        send(listOf(event.player))
    }
}
