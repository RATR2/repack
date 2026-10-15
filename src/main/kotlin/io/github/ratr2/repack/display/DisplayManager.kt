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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.ratr2.repack.block.BlockDefinition
import io.github.ratr2.repack.block.BlockPos
import io.github.ratr2.repack.block.DisplayContext
import io.github.ratr2.repack.block.PlacedBlock
import io.github.ratr2.repack.block.PlacedBlocks
import io.github.ratr2.repack.block.PlacementListener
import io.github.ratr2.repack.model.ItemModelGenerator
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

/**
 * Shows the models. A model is a virtual item display, sent to each player only while that player has the block's chunk loaded,
 * so the view distance sets the range for free. Nothing is spawned on the server and nothing is saved.
 */
class DisplayManager(private val blocks: PlacedBlocks) : Listener, PlacementListener {

    private data class Key(val world: UUID, val pos: BlockPos)

    /** Entity ids of the displays each player currently has. */
    private val shown = HashMap<UUID, HashMap<Key, Int>>()

    override fun placed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        val chunkKey = Chunk.getChunkKey(pos.x shr 4, pos.z shr 4)
        world.players.filter { it.isChunkSent(chunkKey) }.forEach { show(it, world, pos, block, definition) }
    }

    override fun removed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        val key = Key(world.uid, pos)
        world.players.forEach { hide(it, key) }
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

    /** Sends the models again to everyone, for after the definitions or the pack changed. */
    fun refreshAll() {
        for ((playerId, ids) in shown.toMap()) {
            val player = Bukkit.getPlayer(playerId) ?: continue
            ids.keys.toList().forEach { hide(player, it) }
        }
        for (world in Bukkit.getWorlds()) {
            for (player in world.players) {
                for (chunk in world.loadedChunks) {
                    if (!player.isChunkSent(Chunk.getChunkKey(chunk.x, chunk.z))) continue
                    for ((pos, block) in blocks.inChunk(chunk)) {
                        val definition = blocks.definitionOf(block.id) ?: continue
                        show(player, world, pos, block, definition)
                    }
                }
            }
        }
    }

    private fun show(player: Player, world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition) {
        val key = Key(world.uid, pos)
        val ids = shown.getOrPut(player.uniqueId) { HashMap() }
        val entityId = ids.getOrPut(key) { SpigotReflectionUtil.generateEntityId() }

        val options = definition.display
        val location = PacketLocation(
            pos.x + 0.5 + options.offsetX,
            pos.y + 0.5 + options.offsetY,
            pos.z + 0.5 + options.offsetZ,
            block.yaw.toFloat(),
            0f,
        )
        val players = PacketEvents.getAPI().playerManager
        players.sendPacket(
            player,
            WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, location, block.yaw.toFloat(), 0, Vector3d.zero()),
        )
        players.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, metadata(definition)))
    }

    private fun hide(player: Player, key: Key) {
        val entityId = shown[player.uniqueId]?.remove(key) ?: return
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerDestroyEntities(entityId))
    }

    private fun metadata(definition: BlockDefinition): List<EntityData<*>> {
        val options = definition.display
        val item = com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(ItemTypes.PAPER)
            .amount(1)
            .component(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(ItemModelGenerator.NAMESPACE, definition.model)))
            .build()

        val data = ArrayList<EntityData<*>>()
        if (options.scale != 1f) data += EntityData(SCALE, EntityDataTypes.VECTOR3F, Vector3f(options.scale, options.scale, options.scale))
        options.brightness?.let { data += EntityData(BRIGHTNESS, EntityDataTypes.INT, it shl 4 or (it shl 20)) }
        if (options.viewRange != 1f) data += EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, options.viewRange)
        data += EntityData(ITEM, EntityDataTypes.ITEMSTACK, item)
        if (options.context != DisplayContext.NONE) data += EntityData(ITEM_CONTEXT, EntityDataTypes.BYTE, options.context.value)
        return data
    }

    private companion object {
        // Display entity metadata indexes for Minecraft 1.21.4 to 26.3 (position/rotation interpolation exists from 1.21.2).
        const val SCALE = 12
        const val BRIGHTNESS = 16
        const val VIEW_RANGE = 17
        const val ITEM = 23
        const val ITEM_CONTEXT = 24
    }
}
