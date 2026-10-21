package io.github.ratr2.repack.pack

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.RepackCommand
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * The state a player is in while their client reloads a pack the server just sent: the screen freezes, so for at most 20 seconds
 * they cannot be hurt, cannot move, attack or interact, see no other entities, and see the blocks around them as black concrete.
 *
 * It only starts when the client reports downloading the pack Repack just sent, ends when it loads or fails, and the same player
 * can't be held again for two minutes, so a client faking its status gains almost nothing.
 */
class LoadingHold(private val plugin: Repack) : Listener {

    private class Held(val invulnerable: Boolean, val allowFlight: Boolean, val gravity: Boolean, val hidden: MutableSet<UUID> = HashSet())

    private val held = HashMap<UUID, Held>()
    private val timers = HashMap<UUID, BukkitTask>()
    private val lastHeld = HashMap<UUID, Long>()
    private val screen = LoadingScreen(plugin)

    fun isHeld(player: Player) = player.uniqueId in held

    fun start(player: Player) {
        if (!plugin.config.getBoolean("resource-pack.invulnerable-while-loading", true)) return
        val playerId = player.uniqueId
        val now = System.currentTimeMillis()
        if (playerId in held || now - (lastHeld[playerId] ?: 0L) < COOLDOWN_MILLIS) return
        lastHeld[playerId] = now
        val state = Held(player.isInvulnerable, player.allowFlight, player.hasGravity())
        held[playerId] = state

        player.isInvulnerable = true
        // Held in the air: no falling, and allowed to hover so the server's flying check does not kick them while their client is frozen.
        player.allowFlight = true
        player.setGravity(false)
        player.velocity = player.velocity.zero()
        for (entity in player.getNearbyEntities(ENTITY_RANGE, ENTITY_RANGE, ENTITY_RANGE)) hide(player, state, entity)
        plugin.displays()?.hideAllFor(player)
        plugin.modelEntities.hideAllFor(player)
        if (plugin.config.getBoolean("resource-pack.black-blocks-while-loading", true) && plugin.players.bool(playerId, io.github.ratr2.repack.PlayerSettings.LOADING_SCREEN)) screen.show(player)
        timers[playerId] = plugin.server.scheduler.runTaskLater(plugin, Runnable { end(playerId) }, TIMEOUT_TICKS)
    }

    fun end(playerId: UUID) {
        timers.remove(playerId)?.cancel()
        val state = held.remove(playerId) ?: return
        val player = plugin.server.getPlayer(playerId) ?: return
        screen.hide(player)
        for (entityId in state.hidden) plugin.server.getEntity(entityId)?.let { player.showEntity(plugin, it) }
        plugin.displays()?.showAllFor(player)
        plugin.modelEntities.showAllFor(player)
        player.setGravity(state.gravity)
        if (!state.allowFlight) {
            player.isFlying = false
            player.allowFlight = false
        }
        if (!state.invulnerable) player.isInvulnerable = false
    }

    fun endAll() {
        held.keys.toList().forEach(::end)
    }

    private fun hide(player: Player, state: Held, entity: Entity) {
        if (entity == player || entity == player.vehicle) return
        player.hideEntity(plugin, entity)
        state.hidden += entity.uniqueId
    }

    private fun heldPlayer(entity: Entity?): Player? = (entity as? Player)?.takeIf { it.uniqueId in held }

    @EventHandler
    fun onSpawn(event: EntitySpawnEvent) {
        if (held.isEmpty()) return
        for ((playerId, state) in held) {
            val player = plugin.server.getPlayer(playerId) ?: continue
            if (player.world == event.entity.world) hide(player, state, event.entity)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        for ((playerId, state) in held) plugin.server.getPlayer(playerId)?.let { hide(it, state, event.player) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        end(playerId)
        screen.forget(event.player)
        lastHeld.remove(playerId)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onMove(event: PlayerMoveEvent) {
        if (held.isEmpty() || event.player.uniqueId !in held) return
        val from = event.from
        val to = event.to
        if (from.x != to.x || from.y != to.y || from.z != to.z) {
            event.setTo(from.clone().apply { yaw = to.yaw; pitch = to.pitch })
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onAttack(event: EntityDamageByEntityEvent) {
        if (held.isEmpty()) return
        val damager = event.damager
        val attacker = heldPlayer(if (damager is Projectile) damager.shooter as? Entity else damager)
        if (attacker != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (held.isNotEmpty() && event.player.uniqueId in held) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (held.isNotEmpty() && event.player.uniqueId in held) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBreak(event: BlockBreakEvent) {
        if (held.isNotEmpty() && event.player.uniqueId in held) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlace(event: BlockPlaceEvent) {
        if (held.isNotEmpty() && event.player.uniqueId in held) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrop(event: PlayerDropItemEvent) {
        if (held.isNotEmpty() && event.player.uniqueId in held) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onLaunch(event: ProjectileLaunchEvent) {
        if (held.isNotEmpty() && heldPlayer(event.entity.shooter as? Entity) != null) event.isCancelled = true
    }

    private companion object {
        const val TIMEOUT_TICKS = 400L

        const val COOLDOWN_MILLIS = 2L * 60 * 1000

        const val ENTITY_RANGE = 128.0
    }
}
