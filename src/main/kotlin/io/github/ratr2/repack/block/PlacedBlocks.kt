package io.github.ratr2.repack.block

import io.github.ratr2.repack.Repack
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/**
 * Every custom block in a loaded chunk, and the persistence behind it.
 *
 * A custom block is a hidden proxy block (barrier by default) at a position, plus a model shown on top of it. The proxy is what
 * players click and collide with. The list of custom blocks per chunk lives in that chunk's persistent data, so it survives
 * restarts and is saved with the world. The model itself is never saved: it is shown to players as they load the chunk.
 */
class PlacedBlocks(private val plugin: Repack) : Listener {

    private data class ChunkRef(val world: UUID, val x: Int, val z: Int)

    private val chunks = HashMap<ChunkRef, MutableMap<BlockPos, String>>()
    private val dataKey = NamespacedKey(plugin, "blocks")

    private val proxy: Material = plugin.config.getString("proxy-block", "BARRIER")
        ?.let { Material.matchMaterial(it) }
        ?.takeIf { it.isBlock }
        ?: Material.BARRIER

    /** Ids that can be placed: one per .bbmodel file in the models folder. */
    @Volatile
    var types: Set<String> = emptySet()

    fun start() {
        plugin.server.worlds.forEach { world -> world.loadedChunks.forEach(::load) }
    }

    fun idAt(block: Block): String? {
        val ref = ChunkRef(block.world.uid, block.x shr 4, block.z shr 4)
        val blocks = chunks[ref] ?: return null
        val pos = BlockPos(block.x, block.y, block.z)
        val id = blocks[pos] ?: return null
        // Something else (WorldEdit, a command) replaced the proxy, so the entry is stale.
        if (block.type != proxy) {
            blocks.remove(pos)
            save(block.chunk, ref)
            return null
        }
        return id
    }

    /** Places the custom block [id] at [location]. False if the id is unknown or the spot is taken. */
    fun place(location: Location, id: String): Boolean {
        val world = location.world ?: return false
        if (id !in types) return false
        val block = world.getBlockAt(location)
        if (idAt(block) != null || (!block.type.isAir && !block.isReplaceable)) return false

        block.setType(proxy, false)
        val chunk = block.chunk
        val ref = refOf(chunk)
        chunks.getOrPut(ref) { HashMap() }[BlockPos(block.x, block.y, block.z)] = id
        save(chunk, ref)
        return true
    }

    /** Removes the custom block and its proxy at [block]. False if there was none. */
    fun remove(block: Block): Boolean {
        if (idAt(block) == null) return false
        forget(block)
        block.setType(Material.AIR, false)
        return true
    }

    fun inChunk(chunk: Chunk): Map<BlockPos, String> = chunks[refOf(chunk)].orEmpty()

    /** Drops the entry without touching the world, for when the proxy is already gone. */
    private fun forget(block: Block) {
        val ref = ChunkRef(block.world.uid, block.x shr 4, block.z shr 4)
        if (chunks[ref]?.remove(BlockPos(block.x, block.y, block.z)) != null) save(block.chunk, ref)
    }

    private fun refOf(chunk: Chunk) = ChunkRef(chunk.world.uid, chunk.x, chunk.z)

    private fun save(chunk: Chunk, ref: ChunkRef) {
        val blocks = chunks[ref]
        val data = chunk.persistentDataContainer
        if (blocks.isNullOrEmpty()) {
            chunks.remove(ref)
            data.remove(dataKey)
        } else {
            data.set(dataKey, PersistentDataType.STRING, BlockCodec.encode(blocks))
        }
    }

    private fun load(chunk: Chunk) {
        val text = chunk.persistentDataContainer.get(dataKey, PersistentDataType.STRING) ?: return
        val blocks = BlockCodec.decode(text)
        val before = blocks.size
        blocks.keys.removeIf { chunk.world.getBlockAt(it.x, it.y, it.z).type != proxy }
        val ref = refOf(chunk)
        if (blocks.isNotEmpty()) chunks[ref] = blocks
        if (blocks.size != before) save(chunk, ref)
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) = load(event.chunk)

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        chunks.remove(refOf(event.chunk))
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        val id = idAt(block) ?: return
        val custom = CustomBlockInteractEvent(event.player, block, id, event.action, event.hand)
        plugin.server.pluginManager.callEvent(custom)
        if (custom.isCancelled) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = forget(event.block)

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        event.blockList().removeIf { idAt(it) != null }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        event.blockList().removeIf { idAt(it) != null }
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (event.blocks.any { idAt(it) != null }) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (event.blocks.any { idAt(it) != null }) event.isCancelled = true
    }
}
