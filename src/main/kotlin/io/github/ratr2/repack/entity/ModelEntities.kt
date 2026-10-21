package io.github.ratr2.repack.entity

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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.model.ItemModelGenerator
import io.github.ratr2.repack.text.Colors
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** One model entity in the world. Only the manager changes it; use [play], [teleport] and [remove]. */
class ModelEntity internal constructor(
    val uuid: UUID,
    val definitionId: String,
    internal var worldId: UUID,
    var x: Double,
    var y: Double,
    var z: Double,
    var yaw: Float,
    var pitch: Float,
    /** The animation playing now, by name, or null for the still model. */
    var clip: String?,
    internal val entityId: Int,
    private val manager: ModelEntities,
) {
    val location: Location? get() = Bukkit.getWorld(worldId)?.let { Location(it, x, y, z, yaw, pitch) }

    val isValid: Boolean get() = manager.contains(this)

    fun play(clip: String?) = manager.play(this, clip)

    fun teleport(location: Location) = manager.teleport(this, location)

    fun remove() = manager.remove(this)
}

/** The text form of the entities of one chunk, stored in the chunk's persistent data: `uuid id x y z yaw pitch clip` per line, `-` for no clip. */
object EntityCodec {

    class Saved(val uuid: UUID, val id: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float, val clip: String?)

    fun encode(list: Collection<Saved>): String =
        list.sortedBy { it.uuid }.joinToString("\n") { "${it.uuid} ${it.id} ${it.x} ${it.y} ${it.z} ${it.yaw} ${it.pitch} ${it.clip ?: "-"}" }

    /** Skips malformed lines instead of failing, so one bad line cannot lose the rest of the chunk. */
    fun decode(text: String): List<Saved> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(' ')
        if (p.size != 8) return@mapNotNull null
        Saved(
            runCatching { UUID.fromString(p[0]) }.getOrNull() ?: return@mapNotNull null, p[1],
            p[2].toDoubleOrNull() ?: return@mapNotNull null, p[3].toDoubleOrNull() ?: return@mapNotNull null, p[4].toDoubleOrNull() ?: return@mapNotNull null,
            p[5].toFloatOrNull() ?: return@mapNotNull null, p[6].toFloatOrNull() ?: return@mapNotNull null, p[7].takeIf { it != "-" },
        )
    }.toList()
}

/**
 * Model entities: models at any position and rotation, with an animation that can change, shown as packet item displays to the players who have the
 * chunk. Nothing is spawned on the server. They are saved in the chunk they stand in, so they stay through restarts.
 */
class ModelEntities(private val plugin: Repack) : Listener {

    private data class ChunkRef(val world: UUID, val x: Int, val z: Int)

    @Volatile
    var definitions: Map<String, EntityDefinition> = emptyMap()

    private val dataKey = NamespacedKey(plugin, "entities")
    private val loaded = HashMap<UUID, ModelEntity>()
    private val byChunk = HashMap<ChunkRef, MutableSet<UUID>>()
    private val viewers = HashMap<UUID, MutableSet<UUID>>()

    /** Says whether a player must not see models for now (while their client reloads a pack). */
    var hidden: (Player) -> Boolean = { false }

    val all: Collection<ModelEntity> get() = loaded.values.toList()

    fun contains(entity: ModelEntity) = loaded[entity.uuid] === entity

    fun start() {
        for (world in Bukkit.getWorlds()) for (chunk in world.loadedChunks) load(chunk)
    }

    fun stop() {
        for (entity in loaded.values.toList()) viewers[entity.uuid]?.toList()?.forEach { id -> Bukkit.getPlayer(id)?.let { hide(it, entity) } }
        loaded.clear()
        byChunk.clear()
        viewers.clear()
    }

    // ---- Making, changing and removing

    /** Puts a model entity at [location]. Returns null when the world is not loaded or the definition is unknown. */
    fun spawn(definition: EntityDefinition, location: Location, clip: String? = definition.animation): ModelEntity? {
        val world = location.world ?: return null
        val entity = ModelEntity(UUID.randomUUID(), definition.id, world.uid, location.x, location.y, location.z, location.yaw, location.pitch, clip, SpigotReflectionUtil.generateEntityId(), this)
        add(entity)
        save(refOf(entity))
        return entity
    }

    fun remove(entity: ModelEntity) {
        if (!contains(entity)) return
        viewers.remove(entity.uuid)?.forEach { id -> Bukkit.getPlayer(id)?.let { destroy(it, entity) } }
        loaded.remove(entity.uuid)
        val ref = refOf(entity)
        byChunk[ref]?.remove(entity.uuid)
        save(ref)
    }

    fun play(entity: ModelEntity, clip: String?) {
        if (!contains(entity)) return
        entity.clip = clip?.takeIf { it.isNotBlank() }
        save(refOf(entity))
        val definition = definitions[entity.definitionId] ?: return
        viewersOf(entity).forEach { sendMetadata(it, entity, listOf(itemData(definition, entity))) }
    }

    fun teleport(entity: ModelEntity, location: Location) {
        if (!contains(entity)) return
        val world = location.world ?: return
        val before = refOf(entity)
        byChunk[before]?.remove(entity.uuid)
        val sameWorld = world.uid == entity.worldId
        if (!sameWorld) {
            viewers.remove(entity.uuid)?.forEach { id -> Bukkit.getPlayer(id)?.let { destroy(it, entity) } }
        }
        entity.worldId = world.uid
        entity.x = location.x
        entity.y = location.y
        entity.z = location.z
        entity.yaw = location.yaw
        entity.pitch = location.pitch
        val after = refOf(entity)
        byChunk.getOrPut(after) { HashSet() } += entity.uuid
        save(before)
        if (after != before) save(after)
        // Players who have the new chunk see it move or appear, the others lose it.
        val key = Chunk.getChunkKey(after.x, after.z)
        val definition = definitions[entity.definitionId] ?: return
        for (player in world.players) {
            val has = player.isChunkSent(key)
            val sees = viewers[entity.uuid]?.contains(player.uniqueId) == true
            when {
                has && sees -> PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerEntityTeleport(entity.entityId, PacketLocation(entity.x, entity.y, entity.z, entity.yaw, entity.pitch), false))
                has -> show(player, entity, definition)
                sees -> hide(player, entity)
            }
        }
    }

    private fun add(entity: ModelEntity) {
        loaded[entity.uuid] = entity
        byChunk.getOrPut(refOf(entity)) { HashSet() } += entity.uuid
        val definition = definitions[entity.definitionId] ?: return
        val world = Bukkit.getWorld(entity.worldId) ?: return
        val key = Chunk.getChunkKey(refOf(entity).x, refOf(entity).z)
        world.players.filter { it.isChunkSent(key) }.forEach { show(it, entity, definition) }
    }

    // ---- Saving in the chunks

    private fun refOf(entity: ModelEntity) = ChunkRef(entity.worldId, Math.floorDiv(entity.x.toInt(), 16), Math.floorDiv(entity.z.toInt(), 16))

    private fun save(ref: ChunkRef) {
        val world = Bukkit.getWorld(ref.world) ?: return
        if (!world.isChunkLoaded(ref.x, ref.z)) return
        val chunk = world.getChunkAt(ref.x, ref.z, false)
        val list = byChunk[ref].orEmpty().mapNotNull { loaded[it] }.map { EntityCodec.Saved(it.uuid, it.definitionId, it.x, it.y, it.z, it.yaw, it.pitch, it.clip) }
        if (list.isEmpty()) chunk.persistentDataContainer.remove(dataKey) else chunk.persistentDataContainer.set(dataKey, PersistentDataType.STRING, EntityCodec.encode(list))
    }

    private fun load(chunk: Chunk) {
        val text = chunk.persistentDataContainer.get(dataKey, PersistentDataType.STRING) ?: return
        for (saved in EntityCodec.decode(text)) {
            if (saved.uuid in loaded) continue
            val entity = ModelEntity(saved.uuid, saved.id, chunk.world.uid, saved.x, saved.y, saved.z, saved.yaw, saved.pitch, saved.clip, SpigotReflectionUtil.generateEntityId(), this)
            add(entity)
        }
    }

    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) = load(event.chunk)

    @EventHandler
    fun onChunkUnload(event: ChunkUnloadEvent) {
        val ref = ChunkRef(event.world.uid, event.chunk.x, event.chunk.z)
        for (id in byChunk.remove(ref).orEmpty()) {
            val entity = loaded.remove(id) ?: continue
            viewers.remove(id)?.forEach { player -> Bukkit.getPlayer(player)?.let { destroy(it, entity) } }
        }
    }

    // ---- Who sees what

    @EventHandler
    fun onPlayerChunkLoad(event: PlayerChunkLoadEvent) {
        val ref = ChunkRef(event.world.uid, event.chunk.x, event.chunk.z)
        for (id in byChunk[ref].orEmpty()) {
            val entity = loaded[id] ?: continue
            show(event.player, entity, definitions[entity.definitionId] ?: continue)
        }
    }

    @EventHandler
    fun onPlayerChunkUnload(event: PlayerChunkUnloadEvent) {
        val ref = ChunkRef(event.world.uid, event.chunk.x, event.chunk.z)
        for (id in byChunk[ref].orEmpty()) loaded[id]?.let { hide(event.player, it) }
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        // The client discards every entity when it changes dimension, so there is nothing to destroy.
        viewers.values.forEach { it.remove(event.player.uniqueId) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        viewers.values.forEach { it.remove(event.player.uniqueId) }
    }

    private fun viewersOf(entity: ModelEntity): List<Player> = viewers[entity.uuid].orEmpty().mapNotNull { Bukkit.getPlayer(it) }

    /** Takes every model entity away from one player. [showAllFor] gives them back. */
    fun hideAllFor(player: Player) {
        for (entity in loaded.values) if (viewers[entity.uuid]?.contains(player.uniqueId) == true) hide(player, entity)
    }

    fun showAllFor(player: Player) {
        for (entity in loaded.values) {
            val definition = definitions[entity.definitionId] ?: continue
            if (entity.worldId != player.world.uid) continue
            val ref = refOf(entity)
            if (player.isChunkSent(Chunk.getChunkKey(ref.x, ref.z))) show(player, entity, definition)
        }
    }

    /** Sends the entities again to everyone, for after the definitions or the pack changed. */
    fun refreshAll() {
        for (entity in loaded.values) {
            val definition = definitions[entity.definitionId]
            for (player in viewersOf(entity)) {
                hide(player, entity)
                if (definition != null) show(player, entity, definition)
            }
        }
        // Entities whose definition was missing before and exists now.
        for (world in Bukkit.getWorlds()) for (player in world.players) showAllFor(player)
    }

    // ---- Packets

    private fun show(player: Player, entity: ModelEntity, definition: EntityDefinition) {
        if (hidden(player)) return
        val seen = viewers.getOrPut(entity.uuid) { HashSet() }
        if (!seen.add(player.uniqueId)) hide(player, entity).also { seen.add(player.uniqueId) }
        val players = PacketEvents.getAPI().playerManager
        // One bundle, so the client applies the spawn and the item in the same tick and never draws the display without its model.
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(
            player,
            WrapperPlayServerSpawnEntity(entity.entityId, entity.uuid, EntityTypes.ITEM_DISPLAY, PacketLocation(entity.x, entity.y, entity.z, entity.yaw, entity.pitch), entity.yaw, 0, Vector3d.zero()),
        )
        players.sendPacket(player, WrapperPlayServerEntityMetadata(entity.entityId, metadata(definition, entity)))
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    private fun hide(player: Player, entity: ModelEntity) {
        viewers[entity.uuid]?.remove(player.uniqueId)
        destroy(player, entity)
    }

    private fun destroy(player: Player, entity: ModelEntity) {
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerDestroyEntities(entity.entityId))
    }

    private fun sendMetadata(player: Player, entity: ModelEntity, data: List<EntityData<*>>) {
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerEntityMetadata(entity.entityId, data))
    }

    private fun itemData(definition: EntityDefinition, entity: ModelEntity): EntityData<*> {
        val key = entity.clip?.let { "${definition.model}/anim/$it" } ?: definition.model
        val item = com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(ItemTypes.PAPER)
            .amount(1)
            .component(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(ItemModelGenerator.NAMESPACE, key)))
            // Jade and similar mods show the item's name for an item display, which would be "Paper".
            .component(ComponentTypes.ITEM_NAME, Colors.parse(definition.id.split('_', '-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }))
            .build()
        return EntityData(ITEM, EntityDataTypes.ITEMSTACK, item)
    }

    private fun metadata(definition: EntityDefinition, entity: ModelEntity): List<EntityData<*>> {
        val data = ArrayList<EntityData<*>>()
        data += EntityData(FLAGS, EntityDataTypes.BYTE, INVISIBLE)
        data += EntityData(TELEPORT_DURATION, EntityDataTypes.INT, 3)
        if (definition.offsetX != 0.0 || definition.offsetY != 0.0 || definition.offsetZ != 0.0) {
            data += EntityData(TRANSLATION, EntityDataTypes.VECTOR3F, Vector3f(definition.offsetX.toFloat(), definition.offsetY.toFloat(), definition.offsetZ.toFloat()))
        }
        if (definition.scaleX != 1f || definition.scaleY != 1f || definition.scaleZ != 1f) {
            data += EntityData(SCALE, EntityDataTypes.VECTOR3F, Vector3f(definition.scaleX, definition.scaleY, definition.scaleZ))
        }
        definition.brightness?.let { data += EntityData(BRIGHTNESS, EntityDataTypes.INT, it shl 4 or (it shl 20)) }
        if (definition.viewRange != 1f) data += EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, definition.viewRange)
        data += itemData(definition, entity)
        if (definition.context.value != 0.toByte()) data += EntityData(ITEM_CONTEXT, EntityDataTypes.BYTE, definition.context.value)
        return data
    }

    private companion object {
        // Display entity metadata indexes for Minecraft 1.21.4 to 26.3, the same as the block displays use.
        const val FLAGS = 0
        const val INVISIBLE: Byte = 0x20
        const val TELEPORT_DURATION = 10
        const val TRANSLATION = 11
        const val SCALE = 12
        const val BRIGHTNESS = 16
        const val VIEW_RANGE = 17
        const val ITEM = 23
        const val ITEM_CONTEXT = 24
    }
}
