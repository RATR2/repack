package io.github.ratr2.repack.block

import io.github.ratr2.repack.Repack
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.block.Block
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
import org.bukkit.event.block.BlockPistonEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.BoundingBox
import java.util.UUID

/**
 * Turns what happens to proxy blocks into what happens to custom blocks: applies each block's defaults (mining time, drops,
 * explosion and piston behaviour), then fires a [CustomBlockEvent] so anything else can see it and change the outcome.
 */
class BlockEvents(
    private val plugin: Repack,
    private val blocks: PlacedBlocks,
    private val items: BlockItems,
    private val loot: Loot,
) : Listener {

    private class Mining(val block: Block, val definition: BlockDefinition, val startedAt: Long)

    private val mining = HashMap<UUID, Mining>()

    /** Players whose break we are finishing ourselves, so [onBreak] can tell it from a vanilla one. */
    private val completing = HashSet<UUID>()

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tickMining), 1L, 1L)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(blocks::validate), 40L, 40L)
    }

    private fun fire(block: Block, id: String, definition: BlockDefinition, action: BlockAction, player: Player?, cause: Event?, cancelledByDefault: Boolean): CustomBlockEvent {
        val event = CustomBlockEvent(block, id, definition, action, player, cause, cancelledByDefault)
        plugin.server.pluginManager.callEvent(event)
        return event
    }

    // Clicking and placing

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val player = event.player

        val id = blocks.idAt(block)
        if (id != null && !(event.action == Action.RIGHT_CLICK_BLOCK && player.isSneaking && items.idOf(event.item) != null)) {
            val custom = CustomBlockInteractEvent(player, block, id, event.action, event.hand)
            plugin.server.pluginManager.callEvent(custom)
            if (custom.isCancelled) {
                event.isCancelled = true
            } else if (blocks.definitionOf(id)?.vanillaInteraction == false) {
                event.setUseInteractedBlock(Event.Result.DENY)
            }
            return
        }

        val itemId = items.idOf(event.item) ?: return
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand == null) return
        if (id == null && block.type.isInteractable && !player.isSneaking) return
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
        if (plugin.display.locked(hit.block.location)) return false
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
        val player = event.player
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) return
        val block = event.block
        val definition = blocks.definitionAt(block) ?: return
        val id = blocks.idAt(block) ?: return

        val start = fire(block, id, definition, BlockAction.BREAK_START, player, event, cancelledByDefault = false)
        if (start.isCancelled) {
            event.isCancelled = true
            return
        }
        if (definition.proxyMode != ProxyMode.CUSTOM) return

        // Vanilla would otherwise finish the block on its own schedule, or instantly, instead of ours.
        event.instaBreak = false
        val current = mining[player.uniqueId]
        if (current == null || current.block != block) mining[player.uniqueId] = Mining(block, definition, System.currentTimeMillis())
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

    // Breaking

    @EventHandler(ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        val definition = blocks.definitionAt(block) ?: return
        val id = blocks.idAt(block) ?: return
        val player = event.player

        // The proxy's own mining must not finish a block that is on our timer.
        if (definition.proxyMode == ProxyMode.CUSTOM && player.gameMode == GameMode.SURVIVAL && player.uniqueId !in completing) {
            event.isCancelled = true
            return
        }

        val custom = fire(block, id, definition, BlockAction.BREAK, player, event, cancelledByDefault = false)
        if (custom.isCancelled) {
            event.isCancelled = true
            return
        }
        event.isDropItems = false
        event.expToDrop = if (player.gameMode == GameMode.CREATIVE) 0 else definition.breaking.xp
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreakDone(event: BlockBreakEvent) {
        val block = event.block
        val definition = blocks.definitionAt(block) ?: return
        val player = event.player
        blocks.forget(block)
        if (player.gameMode == GameMode.CREATIVE) return
        val needsTool = definition.breaking.dropsNeedTool
        if (needsTool && !Tools.satisfies(player.inventory.itemInMainHand.type.name, definition.breaking)) return
        loot.roll(definition).forEach { block.world.dropItemNaturally(block.location.toCenterLocation(), it) }
    }

    // Explosions, pistons, fire, entities

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) = explode(event.blockList(), event)

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) = explode(event.blockList(), event)

    private fun explode(list: MutableList<Block>, cause: Event) {
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            val definition = blocks.definitionAt(block) ?: continue
            val id = blocks.idAt(block) ?: continue
            val custom = fire(block, id, definition, BlockAction.EXPLODE, null, cause, cancelledByDefault = definition.explosionResistant)
            if (custom.isCancelled) {
                iterator.remove()
                continue
            }
            blocks.forget(block)
            if (definition.explosionDrops) loot.roll(definition).forEach { block.world.dropItemNaturally(block.location.toCenterLocation(), it) }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) = piston(event, event.blocks)

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) = piston(event, event.blocks)

    private fun piston(event: BlockPistonEvent, moved: List<Block>) {
        for (block in moved) {
            val definition = blocks.definitionAt(block) ?: continue
            val id = blocks.idAt(block) ?: continue
            val custom = fire(block, id, definition, BlockAction.PISTON, null, event, cancelledByDefault = definition.piston == PistonBehavior.BLOCK)
            if (custom.isCancelled) {
                event.isCancelled = true
                return
            }
            blocks.forget(block)
            loot.roll(definition).forEach { block.world.dropItemNaturally(block.location.toCenterLocation(), it) }
            block.setType(Material.AIR, false)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        val block = event.block
        val definition = blocks.definitionAt(block) ?: return
        val id = blocks.idAt(block) ?: return
        val custom = fire(block, id, definition, BlockAction.BURN, null, event, cancelledByDefault = false)
        if (custom.isCancelled) event.isCancelled = true else blocks.forget(block)
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityChange(event: EntityChangeBlockEvent) {
        val block = event.block
        val definition = blocks.definitionAt(block) ?: return
        val id = blocks.idAt(block) ?: return
        val custom = fire(block, id, definition, BlockAction.ENTITY_CHANGE, event.entity as? Player, event, cancelledByDefault = false)
        if (custom.isCancelled) event.isCancelled = true else if (event.to != definition.proxyBlock) blocks.forget(block)
    }
}
