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
    private val sending: Boolean = true,
) : Listener {

    private val id: UUID = UUID.nameUUIDFromBytes("repack-pack".toByteArray())

    private val loaded = HashMap<UUID, PackEra>()

    var onLoaded: (Player) -> Unit = {}
    var onLost: (Player, Boolean) -> Unit = { _, _ -> }

    fun hasLoaded(player: Player) = player.uniqueId in loaded

    fun loadedEra(player: Player): PackEra? = loaded[player.uniqueId]

    fun usesPostEffects(player: Player) = loaded[player.uniqueId]?.usesPostEffects == true

    fun supportsPostEffects(player: Player) = variantOf(player)?.usesPostEffects == true

    fun loadedPlayers(): List<Player> = loaded.keys.mapNotNull(plugin.server::getPlayer)

    init {
        if (sending) host.offer(::joinOffer)
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
        if (!sending || !store.hasPublished) return 0
        val online = players.filter { it.isOnline }
        online.forEach { player -> host.enqueue(player.uniqueId) { if (player.isOnline) sendNow(player) } }
        return online.count { player -> variantOf(player)?.let(store.published::get) != null }
    }

    private fun variantOf(player: Player) = PackEra.forProtocol(versions.protocolOf(player))

    /**
     * Sends one player the pack that was built, without publishing it, so an editor can try a change before everyone gets it.
     * [result] is told once the client has loaded it (true) or has not (false: it failed, or the player declined). False when nothing was sent.
     */
    fun sendBuilt(player: Player, result: (Boolean) -> Unit): Boolean {
        if (!sending) return false
        val variant = variantOf(player) ?: return false
        val pack = store.built[variant] ?: return false
        testing[player.uniqueId] = result
        host.enqueue(player.uniqueId) { if (player.isOnline) sendNow(player, pack) else testing.remove(player.uniqueId) }
        return true
    }

    private val testing = HashMap<UUID, (Boolean) -> Unit>()

    private fun sendNow(player: Player, chosen: PackEntry? = null) {
        val variant = variantOf(player)
        if (variant == null) {
            notice(player, "Repack custom blocks are not available on your client version: they need Minecraft 1.21.4 or newer.")
            return
        }
        val pack = chosen ?: store.published[variant] ?: return
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
        // When Repack does not send the pack, whatever pack another plugin sent that the client loads counts as this one.
        val own = event.id == id
        if (!own && sending) return
        val playerId = event.player.uniqueId
        when (event.status) {
            Status.DOWNLOADED -> if (own) {
                val sent = sentAt.remove(playerId)
                if (sent != null && System.currentTimeMillis() - sent < SENT_VALID_MILLIS) hold.start(event.player)
            }
            Status.SUCCESSFULLY_LOADED -> {
                sentAt.remove(playerId)
                if (own) hold.end(playerId)
                if (own) testing.remove(playerId)?.invoke(true)
                val era = variantOf(event.player)
                if (era != null && loaded.put(playerId, era) == null) {
                    onLoaded(event.player)
                }
            }
            Status.DECLINED, Status.FAILED_DOWNLOAD, Status.FAILED_RELOAD, Status.INVALID_URL, Status.DISCARDED -> {
                sentAt.remove(playerId)
                if (own) hold.end(playerId)
                loaded.remove(playerId)?.let { onLost(event.player, it.usesPostEffects) }
                // A discard is the client dropping a pack, not a failure to load one.
                if (own && event.status != Status.DISCARDED) {
                    val test = testing.remove(playerId)
                    if (test != null) test(false) else if (event.status == Status.FAILED_RELOAD) reloadFailed(event.player)
                }
            }
            else -> {}
        }
    }

    /** The client got the pack but could not use it, which is what a shader that does not compile looks like. The reason is only in that player's own log. */
    private fun reloadFailed(player: Player) {
        plugin.logger.warning("${player.name}'s client could not load the Repack pack (FAILED_RELOAD): a shader or a resource in it is broken. Their client log says which.")
        val message = Component.text("${player.name}'s client could not load the Repack pack. A shader or resource in it is broken; their client log says which.", NamedTextColor.RED)
        plugin.server.onlinePlayers.filter { it.hasPermission(io.github.ratr2.repack.RepackCommand.ADMIN) }.forEach { it.sendMessage(message) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        testing.remove(event.player.uniqueId)
        sentAt.remove(event.player.uniqueId)
        loaded.remove(event.player.uniqueId)
    }

    fun fabulousNotice(player: Player) {
        notice(player, "On your client version, distortion effects only show with Fabulous! graphics (Video Settings > Graphics).")
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
        if (sending) host.joined(player.uniqueId)
    }

    private companion object {
        const val SENT_VALID_MILLIS = 5L * 60 * 1000
    }
}
