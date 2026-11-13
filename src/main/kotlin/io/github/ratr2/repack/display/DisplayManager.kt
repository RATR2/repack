package io.github.ratr2.repack.display

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.ratr2.repack.block.BlockDefinition
import io.github.ratr2.repack.block.BlockPos
import io.github.ratr2.repack.block.BreakAnimation
import io.github.ratr2.repack.block.DisplayContext
import io.github.ratr2.repack.block.PlacedBlock
import io.github.ratr2.repack.block.PlacedBlocks
import io.github.ratr2.repack.block.PlacementListener
import io.github.ratr2.repack.model.AnimationMode
import io.github.ratr2.repack.model.AnimationSampler
import io.github.ratr2.repack.model.BoneDisplay
import io.github.ratr2.repack.model.ItemModelGenerator
import io.github.ratr2.repack.model.Rig
import io.github.ratr2.repack.model.RigClip
import io.github.ratr2.repack.model.Vec3
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class DisplayManager(private val plugin: io.github.ratr2.repack.Repack, private val blocks: PlacedBlocks) : Listener, PlacementListener {

    private data class Key(val world: UUID, val pos: BlockPos)

    /** A tile of blocks of one model that share an entity (see [showTiled]): its size and position in tile units, and one entity per player. */
    private data class TileKey(val world: UUID, val model: String, val tx: Int, val y: Int, val tz: Int)

    private class Tile(val entityId: Int, var mask: Int, val size: Int, val definition: BlockDefinition)

    private val tiles = HashMap<UUID, HashMap<TileKey, Tile>>()

    private class Shown(val ids: IntArray, val rig: Rig?, val model: String, var clip: RigClip?, val look: RigLook?, var start: Int = 0, var mode: AnimationMode? = null, val definition: BlockDefinition? = null, val tile: TileKey? = null)

    private class Played(val clip: String?, val start: Int, val mode: AnimationMode?)

    private val played = HashMap<Key, Played>()

    private val locks = HashMap<Key, Int>()

    private class Linger(val player: UUID, val shown: Shown, val until: Int)

    private val lingering = ArrayList<Linger>()

    private val shown = HashMap<UUID, HashMap<Key, Shown>>()

    override fun placed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        val key = Key(world.uid, pos)
        plugin.rigs[definition.model]?.clip(PLACE)?.let { clip ->
            val now = Bukkit.getCurrentTick()
            played[key] = Played(clip.key, now, AnimationMode.ONCE)
            locks[key] = now + ticksOf(clip, AnimationMode.ONCE)
        }
        val chunkKey = Chunk.getChunkKey(pos.x shr 4, pos.z shr 4)
        world.players.filter { it.isChunkSent(chunkKey) }.forEach { show(it, world, pos, block, definition) }
    }

    override fun generated(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        val chunkKey = Chunk.getChunkKey(pos.x shr 4, pos.z shr 4)
        world.players.filter { it.isChunkSent(chunkKey) }.forEach { show(it, world, pos, block, definition) }
    }

    override fun removed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        revealAround(world, pos.x, pos.y, pos.z)
        val key = Key(world.uid, pos)
        val already = played[key]?.clip == BREAK
        played.remove(key)
        locks.remove(key)
        val clip = plugin.rigs[definition.model]?.clip(BREAK)
        // The model of a block with a break animation stays for it (unless it just played in front of the player who broke the block).
        val linger = clip != null && !already && definition.display.breakAnimation != BreakAnimation.OFF
        for (player in world.players) {
            if (linger) lingerOne(player, key, clip!!) else hide(player, key)
        }
    }

    private fun lingerOne(player: Player, key: Key, clip: RigClip) {
        val entry = shown[player.uniqueId]?.remove(key) ?: return
        val rig = entry.rig ?: return RigDisplays.destroy(player, entry.ids)
        val now = Bukkit.getCurrentTick()
        entry.clip = clip
        entry.start = now
        entry.mode = AnimationMode.HOLD
        RigDisplays.update(listOf(player), entry.ids, entry.look ?: return, displaysOf(rig, clip, now, AnimationMode.HOLD), null)
        lingering += Linger(player.uniqueId, entry, now + ticksOf(clip, AnimationMode.HOLD))
    }

    private fun ticksOf(clip: RigClip, mode: AnimationMode): Int =
        Math.ceil((clip.animation.length + if (mode == AnimationMode.ONCE) AnimationSampler.TWEEN_BACK else 0.0) * 20).toInt() + 2

    fun locked(location: org.bukkit.Location): Boolean {
        val world = location.world ?: return false
        val key = Key(world.uid, BlockPos(location.blockX, location.blockY, location.blockZ))
        val until = locks[key] ?: return false
        if (until > Bukkit.getCurrentTick()) return true
        locks.remove(key)
        return false
    }

    fun beginBreak(location: org.bukkit.Location): Int {
        val world = location.world ?: return 0
        val block = location.block
        val definition = blocks.idAt(block)?.let(blocks::definitionOf) ?: return 0
        if (definition.display.breakAnimation != BreakAnimation.DELAY) return 0
        val rig = plugin.rigs[definition.model] ?: return 0
        val clip = rig.clip(BREAK) ?: return 0
        val key = Key(world.uid, BlockPos(block.x, block.y, block.z))
        val now = Bukkit.getCurrentTick()
        played[key] = Played(clip.key, now, AnimationMode.HOLD)
        val ticks = ticksOf(clip, AnimationMode.HOLD)
        locks[key] = now + ticks
        for ((id, mine) in shown) {
            val entry = mine[key] ?: continue
            val player = Bukkit.getPlayer(id) ?: continue
            entry.clip = clip
            entry.start = now
            entry.mode = AnimationMode.HOLD
            RigDisplays.update(listOf(player), entry.ids, entry.look ?: continue, displaysOf(rig, clip, now, AnimationMode.HOLD), null)
        }
        return ticks
    }

    fun unlock(location: org.bukkit.Location) {
        val world = location.world ?: return
        locks.remove(Key(world.uid, BlockPos(location.blockX, location.blockY, location.blockZ)))
    }

    fun reset(location: org.bukkit.Location) {
        val world = location.world ?: return
        val key = Key(world.uid, BlockPos(location.blockX, location.blockY, location.blockZ))
        locks.remove(key)
        if (played.remove(key) == null) return
        for ((id, mine) in shown) {
            val entry = mine[key] ?: continue
            val player = Bukkit.getPlayer(id) ?: continue
            val rig = entry.rig ?: continue
            val base = entry.definition?.let { baseOf(it, rig) } ?: continue
            entry.clip = base.first
            entry.start = 0
            entry.mode = base.second
            RigDisplays.update(listOf(player), entry.ids, entry.look ?: continue, displaysOf(rig, entry.clip, 0, entry.mode), null)
        }
    }

    /** What a block plays when nothing was set on it: the animation its file names, or one called idle (which loops unless the file says otherwise). */
    private fun baseOf(definition: BlockDefinition, rig: Rig): Pair<RigClip?, AnimationMode?> {
        val named = definition.display.animation
        return if (named != null) rig.clip(named) to definition.display.animationMode
        else rig.clip(IDLE) to (definition.display.animationMode ?: AnimationMode.LOOP)
    }

    @EventHandler
    fun onChunkLoad(event: PlayerChunkLoadEvent) {
        val chunk = event.chunk
        for ((pos, block) in blocks.inChunk(chunk)) {
            val definition = blocks.definitionOf(block.id) ?: continue
            show(event.player, chunk.world, pos, block, definition)
        }
    }

    @EventHandler
    fun onChunkUnload(event: PlayerChunkUnloadEvent) {
        val chunk = event.chunk
        for (pos in blocks.inChunk(chunk).keys) hide(event.player, Key(chunk.world.uid, pos))
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        // The client discards every entity when it changes dimension, so there is nothing to destroy.
        shown[event.player.uniqueId]?.keys?.removeIf { it.world == event.from.uid }
        tiles[event.player.uniqueId]?.keys?.removeIf { it.world == event.from.uid }
        buriedKeys.remove(event.player.uniqueId)
        scanned.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        shown.remove(event.player.uniqueId)
        tiles.remove(event.player.uniqueId)
        buriedKeys.remove(event.player.uniqueId)
        scanned.remove(event.player.uniqueId)
    }

    fun hideAllFor(player: Player) {
        shown[player.uniqueId]?.keys?.toList()?.forEach { hide(player, it) }
    }

    fun showAllFor(player: Player) {
        val world = player.world
        for (chunk in world.loadedChunks) {
            if (!player.isChunkSent(Chunk.getChunkKey(chunk.x, chunk.z))) continue
            for ((pos, block) in blocks.inChunk(chunk)) {
                val definition = blocks.definitionOf(block.id) ?: continue
                show(player, world, pos, block, definition)
            }
        }
    }

    fun refreshAll(only: Set<String>? = null) {
        for (world in Bukkit.getWorlds()) {
            for (player in world.players) {
                for (chunk in world.loadedChunks) {
                    if (!player.isChunkSent(Chunk.getChunkKey(chunk.x, chunk.z))) continue
                    for ((pos, block) in blocks.inChunk(chunk)) {
                        if (only != null && block.id !in only) continue
                        hide(player, Key(world.uid, pos))
                        val definition = blocks.definitionOf(block.id) ?: continue
                        show(player, world, pos, block, definition)
                    }
                }
            }
        }
    }

    var hidden: (Player) -> Boolean = { false }

    private fun show(player: Player, world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        if (hidden(player)) return
        val key = Key(world.uid, pos)
        // Showing again without hiding first would spawn a second copy under the same entity id.
        hide(player, key)
        if (far(player, pos, renderDistance(world))) return
        if (buried(world, pos)) {
            buriedKeys.getOrPut(player.uniqueId) { HashSet() } += key
            return
        }
        val ids = shown.getOrPut(player.uniqueId) { HashMap() }

        val options = definition.display
        val location = PacketLocation(
            pos.x + 0.5 + options.offsetX,
            pos.y + 0.5 + options.offsetY,
            pos.z + 0.5 + options.offsetZ,
            block.yaw.toFloat(),
            0f,
        )
        val players = PacketEvents.getAPI().playerManager
        val rig = plugin.rigs[definition.model]
        if (rig != null) {
            val look = lookOf(definition)
            val override = played[key]
            val base = baseOf(definition, rig)
            val clip = if (override != null) override.clip?.let(rig::clip) else base.first
            val start = override?.start ?: 0
            val mode = if (override != null) override.mode else base.second
            val entityIds = RigDisplays.newIds(rig)
            ids[key] = Shown(entityIds, rig, definition.model, clip, look, start, mode, definition)
            RigDisplays.spawn(player, definition.model, rig, entityIds, location, look, displaysOf(rig, clip, start, mode))
            return
        }
        if (showTiled(player, key, pos, block, definition, ids)) return
        val entityId = SpigotReflectionUtil.generateEntityId()
        ids[key] = Shown(intArrayOf(entityId), null, definition.model, null, null)
        // With culling the entity stands at the bottom of the block and the model is lifted half a block (see metadata), so its culling box starts where the block does.
        val spot = if (cullable(definition)) PacketLocation(location.x, location.y - 0.5, location.z, location.yaw, location.pitch) else location
        // One bundle, so the client applies the spawn and the item in the same tick and never draws the display without its model.
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(
            player,
            WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, spot, block.yaw.toFloat(), 0, Vector3d.zero()),
        )
        players.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, metadata(definition)))
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    /** In a dimension that hides buried blocks: whether every side of the block touches a solid block, so nobody could see its model. Unloaded neighbours count as open. */
    private fun buried(world: World, pos: BlockPos): Boolean {
        if (!plugin.dimensions.hidesBuried(world)) return false
        for ((dx, dy, dz) in SIDES) {
            val x = pos.x + dx
            val z = pos.z + dz
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            val y = pos.y + dy
            if (y < world.minHeight || y >= world.maxHeight) return false
            if (!world.getType(x, y, z).isOccluding) return false
        }
        return true
    }

    /** A block next to these coordinates went away, so blocks that were buried may now be visible: sends the ones that are to the players who have the chunk. */
    fun revealAround(world: World, x: Int, y: Int, z: Int) {
        if (!plugin.dimensions.hidesBuried(world)) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            for ((dx, dy, dz) in SIDES) {
                val pos = BlockPos(x + dx, y + dy, z + dz)
                if (!world.isChunkLoaded(pos.x shr 4, pos.z shr 4)) continue
                val block = blocks.inChunk(world, pos.x shr 4, pos.z shr 4)[pos] ?: continue
                val definition = blocks.definitionOf(block.id) ?: continue
                val key = Key(world.uid, pos)
                val chunkKey = Chunk.getChunkKey(pos.x shr 4, pos.z shr 4)
                for (player in world.players) {
                    if (!player.isChunkSent(chunkKey) || shown[player.uniqueId]?.containsKey(key) == true) continue
                    buriedKeys[player.uniqueId]?.remove(key)
                    show(player, world, pos, block, definition)
                }
            }
        })
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: org.bukkit.event.block.BlockBreakEvent) = revealAround(event.block.world, event.block.x, event.block.y, event.block.z)

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: org.bukkit.event.entity.EntityExplodeEvent) = event.blockList().forEach { revealAround(it.world, it.x, it.y, it.z) }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: org.bukkit.event.block.BlockExplodeEvent) = event.blockList().forEach { revealAround(it.world, it.x, it.y, it.z) }

    /** Keys a player has no display for because every side is covered, so the distance scan does not look at them again; [revealAround] clears them. */
    private val buriedKeys = HashMap<UUID, HashSet<Key>>()

    private class Spot(val world: UUID, val x: Double, val y: Double, val z: Double)

    private val scanned = HashMap<UUID, Spot>()

    /** How far from a player (blocks) custom blocks have their display: the dimension's own value, else `display.render-distance`. 0 means no limit. */
    fun renderDistance(world: World): Int = plugin.dimensions.renderDistanceOf(world) ?: plugin.config.getInt("display.render-distance", 48)

    private fun far(player: Player, pos: BlockPos, limit: Int): Boolean {
        if (limit <= 0) return false
        val at = player.location
        val dx = pos.x + 0.5 - at.x
        val dy = pos.y + 0.5 - at.y
        val dz = pos.z + 0.5 - at.z
        return dx * dx + dy * dy + dz * dz > limit.toDouble() * limit
    }

    /**
     * Every few ticks: shows the blocks that came within the render distance of a player and hides the ones that left it (with a margin, so a block on the edge does
     * not flicker). A player who has hardly moved since the last look is skipped.
     */
    fun scan() {
        for (player in Bukkit.getOnlinePlayers()) {
            if (hidden(player)) continue
            val world = player.world
            val limit = renderDistance(world)
            if (limit <= 0) continue
            val at = player.location
            val last = scanned[player.uniqueId]
            if (last != null && last.world == world.uid && (last.x - at.x).let { it * it } + (last.y - at.y).let { it * it } + (last.z - at.z).let { it * it } < MOVE_SQUARED) continue
            scanned[player.uniqueId] = Spot(world.uid, at.x, at.y, at.z)

            val mine = shown[player.uniqueId]
            if (mine != null) {
                val out = limit + HIDE_MARGIN
                val gone = mine.keys.filter { it.world == world.uid && far(player, it.pos, out) }
                gone.forEach { hide(player, it) }
            }
            val skip = buriedKeys[player.uniqueId]
            val reach = limit / 16 + 1
            val cx = at.blockX shr 4
            val cz = at.blockZ shr 4
            for (x in cx - reach..cx + reach) for (z in cz - reach..cz + reach) {
                if (!world.isChunkLoaded(x, z) || !player.isChunkSent(Chunk.getChunkKey(x, z))) continue
                for ((pos, block) in blocks.inChunk(world, x, z)) {
                    if (far(player, pos, limit)) continue
                    val key = Key(world.uid, pos)
                    if (shown[player.uniqueId]?.containsKey(key) == true || skip?.contains(key) == true) continue
                    val definition = blocks.definitionOf(block.id) ?: continue
                    show(player, world, pos, block, definition)
                }
            }
        }
    }

    private fun hide(player: Player, key: Key) {
        buriedKeys[player.uniqueId]?.remove(key)
        val entry = shown[player.uniqueId]?.remove(key) ?: return
        if (entry.tile != null) untile(player, key, entry.tile) else RigDisplays.destroy(player, entry.ids)
    }

    private fun bitOf(pos: BlockPos, size: Int) = 1 shl (Math.floorMod(pos.x, size) + size * Math.floorMod(pos.z, size))

    /**
     * Blocks of a model that has tile models share an entity: one entity shows every block of its type in a tile of size x size blocks, as the item model for that
     * combination (the pack has one for each). A flat surface of 3x3 tiles is a ninth of the entities. Only plain blocks qualify: no rotation, scale, offset or
     * special light, since the tile is one model. Returns false when the block does not.
     */
    private fun showTiled(player: Player, key: Key, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition, ids: HashMap<Key, Shown>): Boolean {
        val size = plugin.merged[definition.model] ?: return false
        val o = definition.display
        if (block.yaw != 0 || o.scaleX != 1f || o.scaleY != 1f || o.scaleZ != 1f || o.offsetX != 0.0 || o.offsetY != 0.0 || o.offsetZ != 0.0 ||
            o.brightness != null || o.viewRange != 1f || o.context != DisplayContext.NONE) return false
        val tileKey = TileKey(key.world, definition.model, Math.floorDiv(pos.x, size), pos.y, Math.floorDiv(pos.z, size))
        val bit = bitOf(pos, size)
        val mine = tiles.getOrPut(player.uniqueId) { HashMap() }
        val existing = mine[tileKey]
        val players = PacketEvents.getAPI().playerManager
        if (existing == null) {
            val entityId = SpigotReflectionUtil.generateEntityId()
            mine[tileKey] = Tile(entityId, bit, size, definition)
            val at = PacketLocation(tileKey.tx * size + size / 2.0, if (cullable(definition)) pos.y.toDouble() else pos.y + 0.5, tileKey.tz * size + size / 2.0, 0f, 0f)
            players.sendPacket(player, WrapperPlayServerBundle())
            players.sendPacket(player, WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, at, 0f, 0, Vector3d.zero()))
            players.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, metadata(definition, ItemModelGenerator.tileModel(definition.model, size, bit), size)))
            players.sendPacket(player, WrapperPlayServerBundle())
        } else {
            existing.mask = existing.mask or bit
            players.sendPacket(player, WrapperPlayServerEntityMetadata(existing.entityId, metadata(definition, ItemModelGenerator.tileModel(definition.model, size, existing.mask), size)))
        }
        ids[key] = Shown(IntArray(0), null, definition.model, null, null, definition = definition, tile = tileKey)
        return true
    }

    private fun untile(player: Player, key: Key, tileKey: TileKey) {
        val mine = tiles[player.uniqueId] ?: return
        val tile = mine[tileKey] ?: return
        tile.mask = tile.mask and bitOf(key.pos, tile.size).inv()
        val players = PacketEvents.getAPI().playerManager
        if (tile.mask == 0) {
            mine.remove(tileKey)
            players.sendPacket(player, WrapperPlayServerDestroyEntities(tile.entityId))
        } else {
            players.sendPacket(player, WrapperPlayServerEntityMetadata(tile.entityId, metadata(tile.definition, ItemModelGenerator.tileModel(tile.definition.model, tile.size, tile.mask), tile.size)))
        }
    }

    private fun lookOf(definition: BlockDefinition): RigLook {
        val options = definition.display
        // The position of the block's own offset is in the location of the displays, so only the scale goes in the transform.
        return RigLook(
            Vec3.ZERO, Vec3(options.scaleX.toDouble(), options.scaleY.toDouble(), options.scaleZ.toDouble()), options.brightness, options.viewRange, options.context.value,
            definition.item.name ?: definition.id.split('_', '-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
        )
    }

    private fun displaysOf(rig: Rig, clip: RigClip?, start: Int, mode: AnimationMode?): List<BoneDisplay> =
        if (clip == null) rig.displays() else rig.displays(AnimationSampler.pose(clip.animation, (Bukkit.getCurrentTick() - start) / 20.0, mode ?: AnimationMode.of(clip.animation.loop)))

    fun play(location: org.bukkit.Location, clip: String?, mode: AnimationMode? = null): Boolean {
        val world = location.world ?: return false
        val block = location.block
        val id = blocks.idAt(block) ?: return false
        val definition = blocks.definitionOf(id) ?: return false
        val rig = plugin.rigs[definition.model] ?: return false
        val key = Key(world.uid, BlockPos(block.x, block.y, block.z))
        val resolved = clip?.let { rig.clip(it) }
        if (clip != null && resolved == null) return false
        val start = Bukkit.getCurrentTick()
        played[key] = Played(resolved?.key, start, mode)
        for ((id2, mine) in shown) {
            val entry = mine[key] ?: continue
            val player = Bukkit.getPlayer(id2) ?: continue
            entry.clip = resolved
            entry.start = start
            entry.mode = mode
            RigDisplays.update(listOf(player), entry.ids, entry.look ?: continue, displaysOf(rig, resolved, start, mode), null)
        }
        return true
    }

    fun playing(location: org.bukkit.Location): String? {
        val world = location.world ?: return null
        val block = location.block
        val key = Key(world.uid, BlockPos(block.x, block.y, block.z))
        played[key]?.let { return it.clip }
        val definition = blocks.idAt(block)?.let(blocks::definitionOf) ?: return null
        val rig = plugin.rigs[definition.model] ?: return null
        return definition.display.animation?.takeIf { rig.clip(it) != null }
    }

    fun animate() {
        val tick = Bukkit.getCurrentTick()
        val previous = lastSent
        val now = HashMap<String, List<BoneDisplay>>()
        for ((id, blocksOf) in shown) {
            val player = Bukkit.getPlayer(id) ?: continue
            for ((key, entry) in blocksOf) {
                val rig = entry.rig ?: continue
                var clip = entry.clip
                if (clip != null && played[key] != null && (entry.mode ?: AnimationMode.of(clip.animation.loop)) == AnimationMode.ONCE && tick - entry.start > ticksOf(clip, AnimationMode.ONCE)) {
                    played.remove(key)
                    val definition = entry.definition
                    if (definition != null) {
                        val base = baseOf(definition, rig)
                        entry.clip = base.first
                        entry.start = 0
                        entry.mode = base.second
                        clip = entry.clip
                        RigDisplays.update(listOf(player), entry.ids, entry.look ?: continue, displaysOf(rig, clip, 0, entry.mode), null)
                    }
                }
                clip ?: continue
                val group = "${entry.model}/${clip.key}/${entry.start}/${entry.mode}"
                now.getOrPut(group) { displaysOf(rig, clip, entry.start, entry.mode) }
                RigDisplays.update(listOf(player), entry.ids, entry.look!!, now.getValue(group), previous[group])
            }
        }
        lastSent = now
        val done = lingering.iterator()
        while (done.hasNext()) {
            val stay = done.next()
            val player = Bukkit.getPlayer(stay.player)
            if (player == null) {
                done.remove()
                continue
            }
            if (tick >= stay.until) {
                RigDisplays.destroy(player, stay.shown.ids)
                done.remove()
                continue
            }
            val entry = stay.shown
            RigDisplays.update(listOf(player), entry.ids, entry.look ?: continue, displaysOf(entry.rig ?: continue, entry.clip, entry.start, entry.mode), null)
        }
    }

    private var lastSent: Map<String, List<BoneDisplay>> = emptyMap()

    /** Whether a block's display gets a culling box: on in the config, and the model is not scaled taller than its block. */
    private fun cullable(definition: BlockDefinition) = plugin.config.getBoolean("display.culling", true) && definition.display.scaleY <= 1f

    private fun cullWidth(definition: BlockDefinition, tile: Int) = maxOf(definition.display.scaleX, definition.display.scaleZ, 1f) * 1.5f * tile

    private fun metadata(definition: BlockDefinition, model: String = definition.model, tile: Int = 1): List<EntityData<*>> {
        val options = definition.display
        val item = com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(ItemTypes.PAPER)
            .amount(1)
            .component(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(ItemModelGenerator.NAMESPACE, model)))
            // Jade and similar mods show the item's name for an item display, which would be "Paper".
            .component(ComponentTypes.ITEM_NAME, io.github.ratr2.repack.text.Colors.parse(definition.item.name ?: definition.id.split('_', '-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }))
            .build()

        val data = ArrayList<EntityData<*>>()
        // The invisible flag, to see whether the client then leaves out the debug hitbox lines. Displays ignore it for rendering.
        data += EntityData(FLAGS, EntityDataTypes.BYTE, INVISIBLE)
        if (options.scaleX != 1f || options.scaleY != 1f || options.scaleZ != 1f) {
            data += EntityData(SCALE, EntityDataTypes.VECTOR3F, Vector3f(options.scaleX, options.scaleY, options.scaleZ))
        }
        options.brightness?.let { data += EntityData(BRIGHTNESS, EntityDataTypes.INT, it shl 4 or (it shl 20)) }
        if (options.viewRange != 1f) data += EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, options.viewRange)
        if (cullable(definition)) {
            // The client never skips drawing a display whose culling box is 0 x 0 (the default), even when it is behind the player. The box runs from the entity up.
            data += EntityData(TRANSLATION, EntityDataTypes.VECTOR3F, Vector3f(0f, 0.5f, 0f))
            data += EntityData(WIDTH, EntityDataTypes.FLOAT, cullWidth(definition, tile))
            data += EntityData(HEIGHT, EntityDataTypes.FLOAT, CULL_HEIGHT)
        }
        data += EntityData(ITEM, EntityDataTypes.ITEMSTACK, item)
        if (options.context != DisplayContext.NONE) data += EntityData(ITEM_CONTEXT, EntityDataTypes.BYTE, options.context.value)
        return data
    }

    private companion object {
        const val MOVE_SQUARED = 4.0
        const val HIDE_MARGIN = 8
        val SIDES = listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))
        const val IDLE = "idle"
        const val PLACE = "place"
        const val BREAK = "break"

        // Display entity metadata indexes for Minecraft 1.21.4 to 26.3 (position/rotation interpolation exists from 1.21.2).
        const val FLAGS = 0
        const val INVISIBLE: Byte = 0x20
        const val TRANSLATION = 11
        const val SCALE = 12
        const val BRIGHTNESS = 16
        const val VIEW_RANGE = 17
        const val WIDTH = 20
        const val HEIGHT = 21
        const val ITEM = 23
        const val ITEM_CONTEXT = 24
        /** Tall enough for a model that reaches two blocks above its own (the vanilla limit is 32 pixels). */
        const val CULL_HEIGHT = 2.5f
    }
}
