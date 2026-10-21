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

class PackSender(
    private val plugin: Repack,
    private val store: PackStore,
    private val host: SharedHost,
    private val versions: ClientVersions,
    private val hold: LoadingHold,
) : Listener {

    private val id: UUID = UUID.nameUUIDFromBytes("repack-pack".toByteArray())

    private val loaded = HashMap<UUID, PackEra>()

    var onLoaded: (Player) -> Unit = {}
    var onLost: (Player, Boolean) -> Unit = { _, _ -> }

    fun hasLoaded(player: Player) = player.uniqueId in loaded

    /** The era of the pack this player has loaded, or null when they have none. */
    fun loadedEra(player: Player): PackEra? = loaded[player.uniqueId]

    fun usesPostEffects(player: Player) = loaded[player.uniqueId]?.usesPostEffects == true

    fun supportsPostEffects(player: Player) = variantOf(player)?.usesPostEffects == true

    fun loadedPlayers(): List<Player> = loaded.keys.mapNotNull(plugin.server::getPlayer)

    init {
        host.offer(::joinOffer)
    }

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

    private fun packForJoin(variant: PackEra): PackEntry? =
        if (plugin.config.getBoolean("resource-pack.join-latest-build", true)) store.built[variant] ?: store.published[variant] else store.published[variant]

    fun send(players: Collection<Player>): Int {
        if (!store.hasPublished) return 0
        val online = players.filter { it.isOnline }
        online.forEach { player -> host.enqueue(player.uniqueId) { if (player.isOnline) sendNow(player) } }
        return online.count { player -> variantOf(player)?.let(store.published::get) != null }
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
            Status.SUCCESSFULLY_LOADED -> {
                sentAt.remove(playerId)
                hold.end(playerId)
                val era = variantOf(event.player)
                if (era != null && loaded.put(playerId, era) == null) {
                    onLoaded(event.player)
                    if (!era.usesPostEffects && plugin.config.getBoolean("distortion.enabled", true)) {
                        notice(event.player, "On your client version, distortion effects only show with Fabulous! graphics (Video Settings > Graphics).")
                    }
                }
            }
            Status.DECLINED, Status.FAILED_DOWNLOAD, Status.FAILED_RELOAD, Status.INVALID_URL, Status.DISCARDED -> {
                sentAt.remove(playerId)
                hold.end(playerId)
                loaded.remove(playerId)?.let { onLost(event.player, it.usesPostEffects) }
            }
            else -> {}
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        sentAt.remove(event.player.uniqueId)
        loaded.remove(event.player.uniqueId)
    }

    private fun notice(player: Player, message: String) {
        if (plugin.config.getBoolean("client-notices", true) && plugin.players.bool(player.uniqueId, io.github.ratr2.repack.PlayerSettings.NOTICES)) player.sendMessage(Component.text(message, NamedTextColor.YELLOW))
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

    private companion object {
        const val SENT_VALID_MILLIS = 5L * 60 * 1000
    }
}
