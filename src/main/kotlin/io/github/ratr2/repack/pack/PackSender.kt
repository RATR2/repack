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
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status
import java.util.UUID

/**
 * Sends the pack to players: the published one when asked, and on join the newest build or the published one, as the config says. It has its own pack id, so a client keeps it next to other server packs (such as
 * Distortion's) and replacing it never touches them.
 */
class PackSender(
    private val plugin: Repack,
    private val store: PackStore,
    private val host: SharedHost,
    private val versions: ClientVersions,
    private val hold: LoadingHold,
) : Listener {

    private val id: UUID = UUID.nameUUIDFromBytes("repack-pack".toByteArray())

    init {
        host.offer(::joinOffer)
    }

    /** What the host sends a joining player for Repack, together with the other plugins' packs in one request. */
    private fun joinOffer(playerId: UUID): Map<String, String>? {
        val player = plugin.server.getPlayer(playerId) ?: return null
        val pack = variantOf(player)?.let(::packForJoin) ?: return null
        val config = plugin.config
        markSent(player)
        return mapOf(
            "id" to id.toString(),
            "url" to host.urlOf("${pack.sha1}.zip").toString(),
            "sha1" to pack.sha1,
            "required" to config.getBoolean("resource-pack.required", false).toString(),
            "prompt" to config.getString("resource-pack.prompt").orEmpty(),
        )
    }

    /** What a joining player gets: the newest build, or with `resource-pack.join-latest-build: false` only what was last pushed. */
    private fun packForJoin(variant: PackVariant): PackEntry? =
        if (plugin.config.getBoolean("resource-pack.join-latest-build", true)) store.built[variant] ?: store.published[variant] else store.published[variant]

    /**
     * Sends the published pack to players, each when it is their turn: spaced out from packs other plugins here send to the
     * same player. Returns how many are queued, 0 when nothing is published.
     */
    fun send(players: Collection<Player>): Int {
        if (!store.hasPublished) return 0
        val online = players.filter { it.isOnline }
        online.forEach { player -> host.enqueue(player.uniqueId) { if (player.isOnline) sendNow(player) } }
        // Only players whose client version has a published pack count as sent.
        return online.count { player -> variantOf(player)?.let(store.published::get) != null }
    }

    /** The version of the pack for the client this player connected with, or null for a client too old for any of them. */
    private fun variantOf(player: Player) = PackVariant.forProtocol(versions.protocolOf(player))

    private fun sendNow(player: Player) {
        val variant = variantOf(player)
        if (variant == null) {
            notice(player, "Repack custom blocks are not available on your client version: they need Minecraft 1.21.4 or newer.")
            return
        }
        val pack = store.published[variant] ?: return
        val config = plugin.config
        markSent(player)
        val request = ResourcePackRequest.resourcePackRequest()
            .packs(ResourcePackInfo.resourcePackInfo(id, host.urlOf("${pack.sha1}.zip"), pack.sha1))
            .required(config.getBoolean("resource-pack.required", false))
            .prompt(Component.text(config.getString("resource-pack.prompt").orEmpty()))
            .build()
        player.sendResourcePacks(request)
    }

    // Between the moment Repack sends a player the pack and the moment their client has loaded it, LoadingHold keeps them still and safe.
    // It only starts when the client reports the download of a pack we sent it in the last five minutes, once per send.

    private val sentAt = HashMap<UUID, Long>()

    private fun markSent(player: Player) {
        sentAt[player.uniqueId] = System.currentTimeMillis()
    }

    @EventHandler
    fun onStatus(event: PlayerResourcePackStatusEvent) {
        if (event.id != id) return
        val playerId = event.player.uniqueId
        when (event.status) {
            Status.DOWNLOADED -> {
                val sent = sentAt.remove(playerId)
                if (sent != null && System.currentTimeMillis() - sent < SENT_VALID_MILLIS) hold.start(event.player)
            }
            Status.SUCCESSFULLY_LOADED, Status.DECLINED, Status.FAILED_DOWNLOAD, Status.FAILED_RELOAD, Status.INVALID_URL, Status.DISCARDED -> {
                sentAt.remove(playerId)
                hold.end(playerId)
            }
            else -> {}
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sentAt.remove(event.player.uniqueId)
    }

    private fun notice(player: Player, message: String) {
        if (plugin.config.getBoolean("client-notices", true)) player.sendMessage(Component.text(message, NamedTextColor.YELLOW))
    }

    /** Tells the shared host a player joined: it sends this pack and the other plugins' together, after the join delay. */
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (variantOf(player) == null) {
            notice(player, "Repack custom blocks are not available on your client version: they need Minecraft 1.21.4 or newer.")
            return
        }
        host.joined(player.uniqueId)
    }

    private companion object {
        /** How long after we send a pack the client's "downloaded" report still counts. */
        const val SENT_VALID_MILLIS = 5L * 60 * 1000
    }
}
