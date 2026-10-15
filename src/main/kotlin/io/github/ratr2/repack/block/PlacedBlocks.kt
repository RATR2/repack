package io.github.ratr2.repack.block

import io.github.ratr2.repack.Repack
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Told whenever a custom block appears or disappears, so the models can be shown and hidden. */
interface PlacementListener {
    fun placed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition)
    fun removed(world: World, pos: BlockPos, block: PlacedBlock, definition: BlockDefinition)
}

/**
 * Every custom block in a loaded chunk, and the persistence behind it.
 *
 * A custom block is a hidden proxy block (barrier by default) with a model shown on top. The list per chunk lives in the chunk's
 * persistent data, so it is saved with the world; the model itself is never saved.
 */
class PlacedBlocks(private val plugin: Repack) : Listener {

    private data class ChunkRef(val world: UUID, val x: Int, val z: Int)

    private val chunks = HashMap<ChunkRef, MutableMap<BlockPos, PlacedBlock>>()
    private val dataKey = NamespacedKey(plugin, "blocks")
    val listeners = CopyOnWriteArrayList<PlacementListener>()

    /** The block types that can be placed, by id. Replaced as a whole when definitions are reloaded. */
    @Volatile
    var definitions: Map<String, BlockDefinition> = emptyMap()

    val types: Set<String> get() = definitions.keys

    fun start() {
        plugin.server.worlds.forEach { world -> world.loadedChunks.forEach(::load) }
    }

    fun definitionOf(id: String): BlockDefinition? = definitions[id]

    fun idAt(block: Block): String? = dataAt(block)?.id

    fun dataAt(block: Block): PlacedBlock? {
        // Called for every liquid flow and piston push on the server, so the common case has to be cheap.
        if (chunks.isEmpty()) return null
        val ref = ChunkRef(block.world.uid, block.x shr 4, block.z shr 4)
        val blocks = chunks[ref] ?: return null
        val pos = BlockPos(block.x, block.y, block.z)
        val data = blocks[pos] ?: return null
        // Something else (WorldEdit, a command) replaced the proxy, so the entry is stale. A type with no definition keeps its
        // entry, so the data is still there if the definition comes back.
        val definition = definitions[data.id]
        if (definition != null && block.type != definition.proxyBlock) {
            forget(block)
            return null
        }
        return data
    }

    fun definitionAt(block: Block): BlockDefinition? = dataAt(block)?.let { definitions[it.id] }

    /** Places the custom block id at location, facing yaw degrees. False if the id is unknown or the spot is taken. */
    fun place(location: Location, id: String, yaw: Float = 0f): Boolean {
        val world = location.world ?: return false
        val definition = definitions[id] ?: return false
        val block = world.getBlockAt(location)
        if (dataAt(block) != null || (!block.type.isAir && !block.isReplaceable)) return false

        block.setType(definition.proxyBlock, false)
        val chunk = block.chunk
        val ref = refOf(chunk)
        val pos = BlockPos(block.x, block.y, block.z)
        val data = PlacedBlock(id, if (definition.display.rotateToPlacer) snap(yaw) else 0)
        chunks.getOrPut(ref) { HashMap() }[pos] = data
        save(chunk, ref)
        listeners.forEach { it.placed(world, pos, data, definition) }
        return true
    }

    /** Removes the custom block and its proxy at block. False if there was none. */
    fun remove(block: Block): Boolean {
        if (dataAt(block) == null) return false
        forget(block)
        block.setType(Material.AIR, false)
        return true
    }

    fun inChunk(chunk: Chunk): Map<BlockPos, PlacedBlock> = chunks[refOf(chunk)].orEmpty()

    fun inChunk(world: World, chunkX: Int, chunkZ: Int): Map<BlockPos, PlacedBlock> = chunks[ChunkRef(world.uid, chunkX, chunkZ)].orEmpty()

    /** Drops the entry without touching the world, for when the proxy is already gone. */
    fun forget(block: Block) {
        val ref = ChunkRef(block.world.uid, block.x shr 4, block.z shr 4)
        val pos = BlockPos(block.x, block.y, block.z)
        val data = chunks[ref]?.remove(pos) ?: return
        save(block.chunk, ref)
        definitions[data.id]?.let { definition -> listeners.forEach { it.removed(block.world, pos, data, definition) } }
    }

    /** Drops every entry whose proxy block is gone, so the models of blocks removed by other means disappear too. */
    fun validate() {
        for ((ref, blocks) in chunks.entries.toList()) {
            val world = plugin.server.getWorld(ref.world) ?: continue
            for ((pos, data) in blocks.entries.toList()) {
                val definition = definitions[data.id] ?: continue
                if (world.getBlockAt(pos.x, pos.y, pos.z).type != definition.proxyBlock) forget(world.getBlockAt(pos.x, pos.y, pos.z))
            }
        }
    }

    private fun snap(yaw: Float): Int = Math.floorMod(Math.round(yaw / 90f) * 90, 360)

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
        blocks.entries.removeIf { (pos, data) ->
            val definition = definitions[data.id]
            definition != null && chunk.world.getBlockAt(pos.x, pos.y, pos.z).type != definition.proxyBlock
        }
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
}
