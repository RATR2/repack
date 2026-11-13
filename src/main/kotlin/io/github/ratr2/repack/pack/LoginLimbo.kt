package io.github.ratr2.repack.pack

import io.github.ratr2.repack.Notices
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.text.Colors
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Login limbo: from the moment a player joins until their client has the pack, they are not really there. Their join message is held back, they are hidden from
 * the tab list and from every other player (so nobody sees someone standing still while the pack downloads, and nobody can fight a player who is about to
 * leave), they cannot be hurt, and (unless `limbo.block-chat` is false) they cannot chat. When their client has loaded the pack, or has failed to or refused to,
 * or after `limbo.timeout-seconds`, they come out: they show up, their join message is sent (or the one from `limbo.join-message`, or none with
 * `limbo.send-join-message: false`), and the notices follow. A player who leaves while in limbo is never announced, so there is no quit message either.
 *
 * Only players Repack will send a pack to are put in limbo: a client too old for it, or a server with nothing published, is not held.
 */
class LoginLimbo(private val plugin: Repack, private val willSend: (Player) -> Boolean) : Listener {

    private class State(val wasInvulnerable: Boolean, val joinMessage: Component?, val timeout: BukkitTask)

    /** Players whose join was never announced (limbo.send-join-message is false), so their quit is not announced either. */
    private val quietQuits = OneShotSet<UUID>()

    /** The notice that is waiting for each player; a player who leaves and comes back must not get the old one on top of the new one. */
    private val noticeTasks = ConcurrentHashMap<UUID, BukkitTask>()

    // Read from the chat thread as well as the main one.
    private val inLimbo = ConcurrentHashMap<UUID, State>()

    // What a player's invulnerability was before limbo, saved in their own data: if the server stops in limbo, the flag limbo set is saved with the player, and
    // this is how the next join knows to put it back instead of taking "invulnerable" for how they were.
    private val wasInvulnerableKey = NamespacedKey(plugin, "limbo-was-invulnerable")

    /** Players whose client has not yet answered about the pack, with the task that gives up on it: they are owed a [RepackJoinEvent], limbo or not. */
    private val awaiting = ConcurrentHashMap<UUID, org.bukkit.scheduler.BukkitTask>()

    fun isInLimbo(player: Player) = inLimbo.containsKey(player.uniqueId)

    /** What a player in limbo was before it (so the loading hold does not take limbo's invulnerability for theirs); null when they are not in limbo. */
    fun originalInvulnerable(player: Player): Boolean? = inLimbo[player.uniqueId]?.wasInvulnerable

    private val enabled get() = plugin.config.getBoolean("limbo.enabled", true)

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // A flag left by a limbo that never ended (the server stopped or crashed): the player gets back what they had.
        player.persistentDataContainer.get(wasInvulnerableKey, PersistentDataType.BYTE)?.let { saved ->
            player.isInvulnerable = saved.toInt() != 0
            player.persistentDataContainer.remove(wasInvulnerableKey)
        }
        // Someone who joins while others are in limbo must not see them either.
        for (id in inLimbo.keys) plugin.server.getPlayer(id)?.let { player.hidePlayer(plugin, it) }
        // A player who is not sent the pack has no shaders to clash with, so there is nothing to tell them and nothing to wait for.
        if (!willSend(player)) {
            val id = player.uniqueId
            // A tick on, so scripts that run on the join itself go first.
            plugin.server.scheduler.runTask(plugin, Runnable { plugin.server.getPlayer(id)?.let { fire(it, RepackJoinEvent.Reason.NOT_SENT) } })
            return
        }
        val seconds = plugin.config.getInt("limbo.timeout-seconds", 30).coerceIn(1, 600)
        val timeout = plugin.server.scheduler.runTaskLater(plugin, Runnable { release(player, RepackJoinEvent.Reason.TIMEOUT) }, seconds * 20L)
        awaiting.remove(player.uniqueId)?.cancel()
        awaiting[player.uniqueId] = timeout
        if (!enabled) {
            afterJoin(player)
            return
        }
        val original = event.joinMessage()
        event.joinMessage(null)
        for (other in plugin.server.onlinePlayers) if (other != player) other.hidePlayer(plugin, player)
        val was = player.isInvulnerable
        inLimbo[player.uniqueId] = State(was, original, timeout)
        player.persistentDataContainer.set(wasInvulnerableKey, PersistentDataType.BYTE, if (was) 1 else 0)
        player.isInvulnerable = true
    }

    private fun fire(player: Player, reason: RepackJoinEvent.Reason) {
        if (plugin.isEnabled && player.isOnline) plugin.server.pluginManager.callEvent(RepackJoinEvent(player, reason))
    }

    /**
     * Brings a player out of limbo: shows them, sends their join message, then the notices. Does nothing for a player who is not in limbo. While the loading
     * hold has the player it is the hold that gives their invulnerability back, so it is left alone here.
     */
    fun release(player: Player, reason: RepackJoinEvent.Reason = RepackJoinEvent.Reason.LOADED) {
        val waiting = awaiting.remove(player.uniqueId)
        waiting?.cancel()
        val state = inLimbo.remove(player.uniqueId)
        state?.timeout?.cancel()
        if (state == null && waiting == null) return
        if (!player.isOnline) return
        if (state != null) {
            restore(player, state, keepInvulnerable = plugin.isLoadingHeld(player))
            // With the plugin stopping nothing more can be scheduled or announced.
            if (!plugin.isEnabled) return
            // A join message that something else cleared before limbo (a vanish plugin) stays unsent, whatever limbo.join-message says.
            if (!plugin.config.getBoolean("limbo.send-join-message", true)) {
                quietQuits.add(player.uniqueId)
            } else if (state.joinMessage != null) {
                plugin.server.broadcast(joinText(plugin.config.getString("limbo.join-message"), player.name) ?: state.joinMessage)
            }
            afterJoin(player)
        }
        fire(player, reason)
    }

    private fun restore(player: Player, state: State, keepInvulnerable: Boolean) {
        if (!keepInvulnerable) player.isInvulnerable = state.wasInvulnerable
        player.persistentDataContainer.remove(wasInvulnerableKey)
        for (other in plugin.server.onlinePlayers) if (other != player) other.showPlayer(plugin, player)
    }

    /** What follows joining, once the player is really there: the notices, a few seconds on so they are read. */
    private fun afterJoin(player: Player) {
        val id = player.uniqueId
        noticeTasks.remove(id)?.cancel()
        // The player is looked up when the time comes, not kept: they may have left and come back as a new object.
        noticeTasks[id] = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            noticeTasks.remove(id)
            plugin.server.getPlayer(id)?.let { plugin.notices.send(it, Notices.SHADERPACKS) }
        }, NOTICE_DELAY_TICKS)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        noticeTasks.remove(event.player.uniqueId)?.cancel()
        awaiting.remove(event.player.uniqueId)?.cancel()
        // Their join was not announced, so neither is their leaving.
        if (quietQuits.take(event.player.uniqueId)) event.quitMessage(null)
        val state = inLimbo.remove(event.player.uniqueId) ?: return
        state.timeout.cancel()
        restore(event.player, state, keepInvulnerable = false)
        // Nobody was told this player joined, so nobody is told they left.
        event.quitMessage(null)
    }

    /** A player who is not really there does not talk: chat from limbo is dropped (the player is told once), unless `limbo.block-chat` is false. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        if (!inLimbo.containsKey(event.player.uniqueId) || !plugin.config.getBoolean("limbo.block-chat", true)) return
        event.isCancelled = true
        event.player.sendMessage(Component.text("You can chat once your pack has loaded.", NamedTextColor.YELLOW))
    }

    /** Brings everyone out, for when the plugin stops: nobody stays hidden or invulnerable, and one player's failure cannot strand the rest. */
    fun releaseAll() {
        noticeTasks.values.forEach { runCatching { it.cancel() } }
        noticeTasks.clear()
        awaiting.values.forEach { runCatching { it.cancel() } }
        awaiting.clear()
        for (id in inLimbo.keys.toList()) {
            val state = inLimbo.remove(id) ?: continue
            runCatching { state.timeout.cancel() }
            val player = plugin.server.getPlayer(id) ?: continue
            runCatching { restore(player, state, keepInvulnerable = false) }.onFailure { plugin.logger.warning("Could not bring ${player.name} out of login limbo: ${it.message}") }
        }
    }

    companion object {
        private const val NOTICE_DELAY_TICKS = 40L

        /**
         * The join message from the config: `{player}` is the name and `&` codes work. Null (use the normal one) when it is empty, or when it comes to no text at
         * all (a message of only colour codes).
         */
        fun joinText(template: String?, name: String): Component? {
            val text = template?.takeIf { it.isNotBlank() }?.let { Colors.parse(it.replace("{player}", name)) } ?: return null
            return text.takeIf { PlainTextComponentSerializer.plainText().serialize(it).isNotBlank() }
        }
    }
}

/** A set whose members are taken out as they are used: [take] says whether the value was in it, and removes it. */
class OneShotSet<T : Any> {
    private val members = ConcurrentHashMap.newKeySet<T>()

    fun add(value: T) = members.add(value)

    fun take(value: T): Boolean = members.remove(value)

    val size: Int get() = members.size
}
