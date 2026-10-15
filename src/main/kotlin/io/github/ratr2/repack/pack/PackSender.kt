package io.github.ratr2.repack.pack

import io.github.ratr2.repack.ClientVersions
import io.github.ratr2.repack.Repack
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.UUID

/**
 * Sends the published pack to players. It has its own pack id, so a client keeps it next to other server packs (such as
 * Distortion's) and replacing it never touches them.
 */
class PackSender(
    private val plugin: Repack,
    private val store: PackStore,
    private val host: SharedHost,
    private val versions: ClientVersions,
) : Listener {

    private val id: UUID = UUID.nameUUIDFromBytes("repack-pack".toByteArray())

    init {
        host.offer(::joinOffer)
    }

    private fun joinOffer(playerId: UUID): Map<String, String>? {
        val player = plugin.server.getPlayer(playerId) ?: return null
        val pack = variantOf(player)?.let(store.published::get) ?: return null
        val config = plugin.config
        return mapOf(
            "id" to id.toString(),
            "url" to host.urlOf("${pack.sha1}.zip").toString(),
            "sha1" to pack.sha1,
            "required" to config.getBoolean("resource-pack.required", false).toString(),
            "prompt" to config.getString("resource-pack.prompt").orEmpty(),
        )
    }

    /** What a joining player gets: the newest build, or with `resource-pack.join-latest-build: false` only what was last pushed. */
    private fun packForJoin(variant: PackEra): PackEntry? =
        if (plugin.config.getBoolean("resource-pack.join-latest-build", true)) store.built[variant] ?: store.published[variant] else store.published[variant]

    fun send(players: Collection<Player>): Int {
        if (!store.hasPublished) return 0
        players.forEach { player -> host.enqueue(player.uniqueId) { if (player.isOnline) sendNow(player) } }
        return players.size
    }

    private fun variantOf(player: Player) = PackEra.forProtocol(versions.protocolOf(player))

    private fun sendNow(player: Player) {
        val variant = variantOf(player)
        if (variant == null) {
            notice(player, "Repack custom blocks are not available on your client version: they need Minecraft 1.21.4 or newer.")
            return
        }
        val pack = store.published[variant] ?: return
        val config = plugin.config
        val request = ResourcePackRequest.resourcePackRequest()
            .packs(ResourcePackInfo.resourcePackInfo(id, host.urlOf("${pack.sha1}.zip"), pack.sha1))
            .required(config.getBoolean("resource-pack.required", false))
            .prompt(Component.text(config.getString("resource-pack.prompt").orEmpty()))
            .build()
        player.sendResourcePacks(request)
    }

    private fun notice(player: Player, message: String) {
        if (plugin.config.getBoolean("client-notices", true)) player.sendMessage(Component.text(message, NamedTextColor.YELLOW))
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (variantOf(player) == null) {
            notice(player, "Repack custom blocks are not available on your client version: they need Minecraft 1.21.4 or newer.")
            return
        }
        host.joined(player.uniqueId)
    }
}
