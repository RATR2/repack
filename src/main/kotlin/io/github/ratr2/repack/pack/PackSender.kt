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
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

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

    // The screen freezes while the client reloads a pack, so a player cannot be hurt between the moment the client says it has the file
    // and the moment it has loaded it (or failed). A modified client could try to stretch that, so:
    //  - it only starts when the client reports the pack we sent it a moment ago, once per send,
    //  - it ends after 20 seconds whatever the client says, and on any hit the player deals,
    //  - and the same player cannot get it again for two minutes.
    // The player's own invulnerable setting is put back when it ends, when they leave and when the plugin stops.

    private val sentAt = HashMap<UUID, Long>()
    private val lastProtected = HashMap<UUID, Long>()
    private val protectedPlayers = HashMap<UUID, Boolean>()
    private val protectTimers = HashMap<UUID, BukkitTask>()

    private fun markSent(player: Player) {
        sentAt[player.uniqueId] = System.currentTimeMillis()
    }

    private fun protect(player: Player) {
        if (!plugin.config.getBoolean("resource-pack.invulnerable-while-loading", true)) return
        val playerId = player.uniqueId
        val now = System.currentTimeMillis()
        if (playerId in protectedPlayers) return
        if (now - (lastProtected[playerId] ?: 0L) < PROTECT_COOLDOWN_MILLIS) return
        lastProtected[playerId] = now
        protectedPlayers[playerId] = player.isInvulnerable
        player.isInvulnerable = true
        protectTimers[playerId] = plugin.server.scheduler.runTaskLater(plugin, Runnable { release(playerId) }, PROTECT_TIMEOUT_TICKS)
    }

    private fun release(playerId: UUID) {
        protectTimers.remove(playerId)?.cancel()
        val wasInvulnerable = protectedPlayers.remove(playerId) ?: return
        if (!wasInvulnerable) plugin.server.getPlayer(playerId)?.isInvulnerable = false
    }

    fun releaseAll() {
        protectedPlayers.keys.toList().forEach(::release)
    }

    @EventHandler
    fun onStatus(event: PlayerResourcePackStatusEvent) {
        if (event.id != id) return
        val playerId = event.player.uniqueId
        when (event.status) {
            Status.DOWNLOADED -> {
                val sent = sentAt.remove(playerId)
                if (sent != null && System.currentTimeMillis() - sent < SENT_VALID_MILLIS) protect(event.player)
            }
            Status.SUCCESSFULLY_LOADED, Status.DECLINED, Status.FAILED_DOWNLOAD, Status.FAILED_RELOAD, Status.INVALID_URL, Status.DISCARDED -> {
                sentAt.remove(playerId)
                release(playerId)
            }
            else -> {}
        }
    }

    /** A protected player who hits something loses the protection, so it cannot be used to fight without risk. */
    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val attacker = if (damager is Projectile) damager.shooter as? Player else damager as? Player
        if (attacker != null) release(attacker.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        release(playerId)
        sentAt.remove(playerId)
        lastProtected.remove(playerId)
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

    private companion object {
        const val SENT_VALID_MILLIS = 5L * 60 * 1000

        /** The least time between two protections of the same player. */
        const val PROTECT_COOLDOWN_MILLIS = 2L * 60 * 1000
    }
}
