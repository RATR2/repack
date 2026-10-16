package io.github.ratr2.repack.block

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.protect.Protection
import io.papermc.paper.event.block.BlockBreakBlockEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.ExplosionResult
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFertilizeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockPistonEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityInteractEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerBucketEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.BoundingBox
import java.util.UUID

/**
 * Turns what happens to proxy blocks into what happens to custom blocks. For every way a block can be changed or used, it applies
 * the block's defaults, then fires a CustomBlockEvent so anything else can change the outcome.
 *
 * The event is fired and the Bukkit event cancelled or trimmed at normal priority, alongside other plugins. The custom block is
 * only removed at monitor priority, once the outcome is final, so a protection plugin stepping in later can't lose the block.
 */
private const val DENY_COOLDOWN_MILLIS = 3000L
/** Vanilla block interaction range is 4.5 (5 in creative); the attribute follows anything that raises it, and one block of slack covers lag. */
internal fun reachOf(player: org.bukkit.entity.Player): Int {
    val range = player.getAttribute(org.bukkit.attribute.Attribute.BLOCK_INTERACTION_RANGE)?.value ?: 4.5
    return kotlin.math.ceil(range).toInt() + 1
}
private const val SWING_TIMEOUT_MILLIS = 700L

class BlockEvents(
    private val plugin: Repack,
    private val blocks: PlacedBlocks,
    private val items: BlockItems,
    private val loot: Loot,
    private val protection: Protection,
) : Listener {

    private class Mining(val block: Block, val definition: BlockDefinition, val startedAt: Long, var lastActive: Long)

    private class Hit(val block: Block, val id: String, val definition: BlockDefinition)

    private val mining = HashMap<UUID, Mining>()
    private val lastDenied = HashMap<UUID, Long>()

    /** Players whose break we are finishing ourselves, so onBreak can tell it from a vanilla one. */
    private val completing = HashSet<UUID>()

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tickMining), 1L, 1L)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(blocks::validate), 40L, 40L)
    }

    private fun hit(block: Block): Hit? {
        val data = blocks.dataAt(block) ?: return null
        val definition = blocks.definitionOf(data.id) ?: return null
        return Hit(block, data.id, definition)
    }

    private fun fire(hit: Hit, action: BlockAction, player: Player?, cause: Event?, cancelledByDefault: Boolean): CustomBlockEvent {
        val event = CustomBlockEvent(hit.block, hit.id, hit.definition, action, player, cause, cancelledByDefault)
        plugin.server.pluginManager.callEvent(event)
        return event
    }

    /** Tells the player in chat, at most every few seconds. The action bar is left alone: servers use it for their own icons. */
    private fun deny(player: Player) {
        val text = plugin.config.getString("messages.no-permission", "You can't do that here.").orEmpty()
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - (lastDenied[player.uniqueId] ?: 0L) < DENY_COOLDOWN_MILLIS) return
        lastDenied[player.uniqueId] = now
        player.sendMessage(Component.text(text, NamedTextColor.RED))
    }

    private fun debug(message: String) {
        if (plugin.config.getBoolean("debug", false)) plugin.logger.info("[debug] $message")
    }

    private fun dropLoot(hit: Hit) {
        loot.roll(hit.definition).forEach { hit.block.world.dropItemNaturally(hit.block.location.toCenterLocation(), it) }
    }

    // Clicking and placing. Runs after protection plugins (which act at normal priority) so their verdict is visible.

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player
        val hit = hit(block)

        if (hit != null && event.action == Action.PHYSICAL) {
            val custom = CustomBlockInteractEvent(player, block, hit.id, event.action, null)
            plugin.server.pluginManager.callEvent(custom)
            if (custom.isCancelled) event.isCancelled = true
            return
        }
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return

        val holdsBlockItem = items.idOf(event.item) != null
        if (hit != null && !(event.action == Action.RIGHT_CLICK_BLOCK && player.isSneaking && holdsBlockItem)) {
            if (event.useInteractedBlock() == Event.Result.DENY || event.isCancelled) return
            if (!protection.canInteract(player, block)) {
                event.setUseInteractedBlock(Event.Result.DENY)
                deny(player)
                return
            }
            if (event.action == Action.LEFT_CLICK_BLOCK) {
                debug("${player.name} left clicked ${hit.id}")
                if (!beginMining(player, hit, event)) {
                    event.setUseInteractedBlock(Event.Result.DENY)
                    return
                }
            }
            val custom = CustomBlockInteractEvent(player, block, hit.id, event.action, event.hand)
            plugin.server.pluginManager.callEvent(custom)
            if (custom.isCancelled) {
                event.isCancelled = true
            } else if (!hit.definition.vanillaInteraction && (event.action == Action.RIGHT_CLICK_BLOCK || (hit.definition.proxyMode == ProxyMode.CUSTOM && player.gameMode != GameMode.CREATIVE))) {
                // Denying a left click would also stop the proxy block's own digging: vanilla mode relies on it, and creative breaks instantly.
                event.setUseInteractedBlock(Event.Result.DENY)
            }
            return
        }

        val itemId = items.idOf(event.item) ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand == null) return
        if (event.useItemInHand() == Event.Result.DENY || event.isCancelled) return
        if (hit == null && block.type.isInteractable && !player.isSneaking && event.useInteractedBlock() != Event.Result.DENY) return
        place(event, itemId, block)
    }

    private fun place(event: PlayerInteractEvent, id: String, clicked: Block) {
        val definition = blocks.definitionOf(id) ?: return
        val player = event.player
        val item = event.item ?: return
        val target = if (clicked.isReplaceable && blocks.idAt(clicked) == null) clicked else clicked.getRelative(event.blockFace)
        if ((!target.type.isAir && !target.isReplaceable) || blocks.idAt(target) != null) return
        if (definition.proxyBlock.isSolid) {
            val occupied = target.world.getNearbyEntities(BoundingBox.of(target)) { it is LivingEntity && !it.isDead }.isNotEmpty()
            if (occupied) return
        }

        val replaced = target.state
        if (!blocks.place(target.location, id, player.location.yaw)) return
        val hand = event.hand ?: EquipmentSlot.HAND
        // Protection plugins judge this like any other block placement.
        val placeEvent = BlockPlaceEvent(target, replaced, clicked, item, player, true, hand)
        plugin.server.pluginManager.callEvent(placeEvent)
        event.isCancelled = true
        if (placeEvent.isCancelled || !placeEvent.canBuild()) {
            blocks.remove(target)
            replaced.update(true, false)
            return
        }
        if (player.gameMode != GameMode.CREATIVE) item.amount -= 1
        player.swingHand(hand)
    }

    // Mining

    /**
     * Starts the mining timer for a custom block the player has begun to dig, or keeps the one already running for it. Returns
     * false when the dig is refused. Called from the block damage event and from the left click, because some proxy blocks
     * (the barrier) may never send the first.
     */
    private fun beginMining(player: Player, hit: Hit, cause: Event): Boolean {
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) return true
        val now = System.currentTimeMillis()
        val running = mining[player.uniqueId]
        if (running != null && running.block == hit.block) {
            running.lastActive = now
            return true
        }
        if (!protection.canBreak(player, hit.block)) {
            debug("${player.name} may not break ${hit.id} at ${hit.block.x} ${hit.block.y} ${hit.block.z} here")
            deny(player)
            return false
        }
        if (fire(hit, BlockAction.BREAK_START, player, cause, cancelledByDefault = false).isCancelled) return false
        if (hit.definition.proxyMode == ProxyMode.CUSTOM) {
            mining[player.uniqueId] = Mining(hit.block, hit.definition, now, now)
            debug("${player.name} started mining ${hit.id} (${hit.definition.breaking.time} s), triggered by ${cause.eventName}")
        }
        return true
    }

    @EventHandler(ignoreCancelled = true)
    fun onDamage(event: BlockDamageEvent) {
        val hit = hit(event.block) ?: return
        debug("block damage event for ${hit.id}")
        if (!beginMining(event.player, hit, event)) {
            event.isCancelled = true
            return
        }
        // Vanilla would otherwise finish the block instantly, instead of on our timer.
        if (hit.definition.proxyMode == ProxyMode.CUSTOM) event.instaBreak = false
    }

    /** Swings keep a dig alive: the client swings every few ticks while the button is held, and stops when it lets go. */
    @EventHandler
    fun onSwing(event: PlayerAnimationEvent) {
        if (event.animationType != PlayerAnimationType.ARM_SWING) return
        mining[event.player.uniqueId]?.lastActive = System.currentTimeMillis()
    }

    @EventHandler
    fun onDamageAbort(event: BlockDamageAbortEvent) {
        val session = mining[event.player.uniqueId] ?: return
        if (session.block != event.block) return
        mining.remove(event.player.uniqueId)
        event.player.sendBlockDamage(event.block.location, 0f)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        mining.remove(event.player.uniqueId)
        lastDenied.remove(event.player.uniqueId)
    }

    private fun tickMining() {
        if (mining.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((playerId, session) in mining.entries.toList()) {
            val player = plugin.server.getPlayer(playerId)
            if (player == null || blocks.idAt(session.block) == null) {
                mining.remove(playerId)
                continue
            }
            // Stopped digging: looked away, or no swing for a moment (the abort packet does not always arrive for every proxy).
            if (player.getTargetBlockExact(reachOf(player)) != session.block || now - session.lastActive > SWING_TIMEOUT_MILLIS) {
                mining.remove(playerId)
                player.sendBlockDamage(session.block.location, 0f)
                debug("${player.name} stopped mining ${session.definition.id}")
                continue
            }
            val progress = ((now - session.startedAt) / (session.definition.breaking.time * 1000.0)).toFloat().coerceAtMost(1f)
            player.sendBlockDamage(session.block.location, progress)
            if (progress >= 1f) finishBreak(player, session)
        }
    }

    private fun finishBreak(player: Player, session: Mining) {
        mining.remove(player.uniqueId)
        val block = session.block
        val event = BlockBreakEvent(block, player)
        completing += player.uniqueId
        try {
            plugin.server.pluginManager.callEvent(event)
        } finally {
            completing -= player.uniqueId
        }
        if (event.isCancelled) {
            player.sendBlockDamage(block.location, 0f)
            return
        }
        block.setType(Material.AIR, false)
        val tool = player.inventory.itemInMainHand
        if (!tool.type.isAir) player.damageItemStack(EquipmentSlot.HAND, 1)
        if (event.expToDrop > 0) {
            block.world.spawn(block.location.toCenterLocation(), ExperienceOrb::class.java) { it.experience = event.expToDrop }
        }
    }

    // Breaking by a player

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val hit = hit(event.block) ?: return
        val player = event.player

        // The proxy's own mining must not finish a block that is on our timer.
        if (hit.definition.proxyMode == ProxyMode.CUSTOM && player.gameMode == GameMode.SURVIVAL && player.uniqueId !in completing) {
            event.isCancelled = true
            return
        }
        if (fire(hit, BlockAction.BREAK, player, event, cancelledByDefault = false).isCancelled) {
            event.isCancelled = true
            return
        }
        event.isDropItems = false
        event.expToDrop = if (player.gameMode == GameMode.CREATIVE) 0 else hit.definition.breaking.xp
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreakDone(event: BlockBreakEvent) {
        val hit = hit(event.block) ?: return
        val player = event.player
        blocks.forget(event.block)
        if (player.gameMode == GameMode.CREATIVE) return
        if (hit.definition.breaking.dropsNeedTool && !Tools.satisfies(player.inventory.itemInMainHand.type.name, hit.definition.breaking)) return
        dropLoot(hit)
    }

    // Explosions

    private fun destroys(result: ExplosionResult) = result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        if (destroys(event.explosionResult)) explode(event.blockList(), event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplodeDone(event: EntityExplodeEvent) {
        if (destroys(event.explosionResult)) exploded(event.blockList())
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        if (destroys(event.explosionResult)) explode(event.blockList(), event)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplodeDone(event: BlockExplodeEvent) {
        if (destroys(event.explosionResult)) exploded(event.blockList())
    }

    private fun explode(list: MutableList<Block>, cause: Event) {
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val hit = hit(iterator.next()) ?: continue
            if (fire(hit, BlockAction.EXPLODE, null, cause, cancelledByDefault = hit.definition.explosionResistant).isCancelled) iterator.remove()
        }
    }

    private fun exploded(list: List<Block>) {
        for (block in list) {
            val hit = hit(block) ?: continue
            blocks.forget(block)
            if (hit.definition.explosionDrops) dropLoot(hit)
        }
    }

    // Pistons

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) = piston(event, event.blocks)

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) = piston(event, event.blocks)

    private fun piston(event: BlockPistonEvent, moved: List<Block>) {
        for (block in moved) {
            val hit = hit(block) ?: continue
            if (fire(hit, BlockAction.PISTON, null, event, cancelledByDefault = hit.definition.piston == PistonBehavior.BLOCK).isCancelled) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtendDone(event: BlockPistonExtendEvent) = pistoned(event.blocks)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetractDone(event: BlockPistonRetractEvent) = pistoned(event.blocks)

    /** A custom block the piston was allowed to move cannot move, so it is destroyed instead. */
    private fun pistoned(moved: List<Block>) {
        for (block in moved) {
            val hit = hit(block) ?: continue
            blocks.forget(block)
            dropLoot(hit)
            block.setType(Material.AIR, false)
        }
    }

    // Fire, entities, physics

    @EventHandler(ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        val hit = hit(event.block) ?: return
        if (fire(hit, BlockAction.BURN, null, event, cancelledByDefault = false).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBurnDone(event: BlockBurnEvent) {
        if (hit(event.block) != null) blocks.forget(event.block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChange(event: EntityChangeBlockEvent) {
        val hit = hit(event.block) ?: return
        if (fire(hit, BlockAction.ENTITY_CHANGE, event.entity as? Player, event, cancelledByDefault = false).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeDone(event: EntityChangeBlockEvent) {
        val hit = hit(event.block) ?: return
        if (event.to != hit.definition.proxyBlock) blocks.forget(event.block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityInteract(event: EntityInteractEvent) {
        val hit = hit(event.block) ?: return
        if (fire(hit, BlockAction.ENTITY_INTERACT, event.entity as? Player, event, cancelledByDefault = false).isCancelled) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        val hit = hit(event.block) ?: return
        if (fire(hit, BlockAction.FADE, null, event, cancelledByDefault = false).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFadeDone(event: BlockFadeEvent) {
        val hit = hit(event.block) ?: return
        if (event.newState.type != hit.definition.proxyBlock) blocks.forget(event.block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        val hit = hit(event.block) ?: return
        if (fire(hit, BlockAction.FADE, null, event, cancelledByDefault = false).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecayDone(event: LeavesDecayEvent) {
        if (hit(event.block) != null) blocks.forget(event.block)
    }

    /** Liquids flowing into a block that can be replaced. Protected by default. */
    @EventHandler(ignoreCancelled = true)
    fun onFluid(event: BlockFromToEvent) {
        val hit = hit(event.toBlock) ?: return
        if (fire(hit, BlockAction.FLUID, null, event, cancelledByDefault = true).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFluidDone(event: BlockFromToEvent) {
        if (hit(event.toBlock) != null) blocks.forget(event.toBlock)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) = bucket(event, event.block)

    @EventHandler(ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) = bucket(event, event.block)

    private fun bucket(event: PlayerBucketEvent, affected: Block) {
        val hit = hit(affected) ?: return
        if (fire(hit, BlockAction.BUCKET, event.player, event, cancelledByDefault = true).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBucketEmptyDone(event: PlayerBucketEmptyEvent) {
        if (hit(event.block) != null) blocks.forget(event.block)
    }

    // Growth replacing blocks

    @EventHandler(ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) = grow(event.blocks, event)

    @EventHandler(ignoreCancelled = true)
    fun onFertilize(event: BlockFertilizeEvent) = grow(event.blocks, event)

    private fun grow(states: MutableList<BlockState>, cause: Event) {
        val iterator = states.iterator()
        while (iterator.hasNext()) {
            val hit = hit(iterator.next().block) ?: continue
            if (fire(hit, BlockAction.GROW, null, cause, cancelledByDefault = true).isCancelled) iterator.remove()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrowDone(event: StructureGrowEvent) = grown(event.blocks)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFertilizeDone(event: BlockFertilizeEvent) = grown(event.blocks)

    private fun grown(states: List<BlockState>) {
        for (state in states) if (hit(state.block) != null) blocks.forget(state.block)
    }

    /** A block destroyed because it lost its support, such as a torch whose wall went away. */
    @EventHandler(ignoreCancelled = true)
    fun onDestroy(event: BlockDestroyEvent) {
        val hit = hit(event.block) ?: return
        if (fire(hit, BlockAction.PHYSICS, null, event, cancelledByDefault = false).isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDestroyDone(event: BlockDestroyEvent) {
        if (hit(event.block) != null) blocks.forget(event.block)
    }

    /** A block broken by another block, such as flowing water. This one cannot be cancelled, only observed. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onBrokenByBlock(event: BlockBreakBlockEvent) {
        val hit = hit(event.block) ?: return
        fire(hit, BlockAction.BROKEN_BY_BLOCK, null, event, cancelledByDefault = false)
        blocks.forget(event.block)
    }
}
