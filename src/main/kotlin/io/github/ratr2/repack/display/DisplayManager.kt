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

    private class Shown(val ids: IntArray, val rig: Rig?, val model: String, var clip: RigClip?, val look: RigLook?, var start: Int = 0, var mode: AnimationMode? = null, val definition: BlockDefinition? = null)

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

    override fun removed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
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
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        shown.remove(event.player.uniqueId)
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
        val entityId = SpigotReflectionUtil.generateEntityId()
        ids[key] = Shown(intArrayOf(entityId), null, definition.model, null, null)
        // One bundle, so the client applies the spawn and the item in the same tick and never draws the display without its model.
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(
            player,
            WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, location, block.yaw.toFloat(), 0, Vector3d.zero()),
        )
        players.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, metadata(definition)))
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    private fun hide(player: Player, key: Key) {
        val entry = shown[player.uniqueId]?.remove(key) ?: return
        RigDisplays.destroy(player, entry.ids)
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

    private fun metadata(definition: BlockDefinition): List<EntityData<*>> {
        val options = definition.display
        val item = com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(ItemTypes.PAPER)
            .amount(1)
            .component(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(ItemModelGenerator.NAMESPACE, definition.model)))
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
        data += EntityData(ITEM, EntityDataTypes.ITEMSTACK, item)
        if (options.context != DisplayContext.NONE) data += EntityData(ITEM_CONTEXT, EntityDataTypes.BYTE, options.context.value)
        return data
    }

    private companion object {
        const val IDLE = "idle"
        const val PLACE = "place"
        const val BREAK = "break"

        // Display entity metadata indexes for Minecraft 1.21.4 to 26.3 (position/rotation interpolation exists from 1.21.2).
        const val FLAGS = 0
        const val INVISIBLE: Byte = 0x20
        const val SCALE = 12
        const val BRIGHTNESS = 16
        const val VIEW_RANGE = 17
        const val ITEM = 23
        const val ITEM_CONTEXT = 24
    }
}
