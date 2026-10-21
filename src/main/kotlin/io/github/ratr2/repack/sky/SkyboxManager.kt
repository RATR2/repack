package io.github.ratr2.repack.sky

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.component.ComponentTypes
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemDyeColor
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.resources.ResourceLocation
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.pack.PackSender
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.UUID

/**
 * Shows each player the skybox that fits where they are. The sky is a packet-only item display, scaled inside out and sent as a
 * passenger of the player's own entity, so it follows them without teleports. The pack's item shader draws it (see SkyboxShaders);
 * the time of day and the player's height reach the shader in the item's color.
 *
 * The idea is SkyboxEngine's (Dragoncraft000, MIT, see NOTICE).
 */
class SkyboxManager(private val plugin: Repack, private val pack: PackSender) : Listener {

    private class Shown(val entityId: Int, var sky: SkyboxDefinition, var size: Int, var color: Int, var resentAt: Int)

    private val chosen = HashMap<UUID, String>()
    private val shown = java.util.concurrent.ConcurrentHashMap<UUID, Shown>()

    @Volatile
    var definitions: List<SkyboxDefinition> = emptyList()
        private set

    private val location = java.util.concurrent.ConcurrentHashMap<UUID, String>()

    private val listener = object : PacketListenerAbstract() {
        // A packet that sets the passengers of a player replaces all of them, so ours is put back into it.
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.packetType != PacketType.Play.Server.SET_PASSENGERS) return
            val uuid = event.user?.uuid ?: return
            val mine = shown[uuid] ?: return
            val wrapper = WrapperPlayServerSetPassengers(event)
            val player = Bukkit.getPlayer(uuid) ?: return
            if (wrapper.entityId != player.entityId || mine.entityId in wrapper.passengers) return
            wrapper.passengers = wrapper.passengers + mine.entityId
            event.markForReEncode(true)
        }
    }

    init {
        PacketEvents.getAPI().eventManager.registerListener(listener)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tick), 2L, 2L)
    }

    fun close() {
        PacketEvents.getAPI().eventManager.unregisterListener(listener)
        val old = shown.toMap()
        shown.clear()
        for ((id, mine) in old) Bukkit.getPlayer(id)?.let { destroy(it, mine) }
    }

    fun load(skies: List<SkyboxDefinition>) {
        definitions = skies
        // Every display is rebuilt: the codes may have changed with the set.
        val old = shown.toMap()
        shown.clear()
        for ((id, mine) in old) Bukkit.getPlayer(id)?.let { destroy(it, mine) }
        location.clear()
    }

    fun skyboxOf(player: Player): SkyboxDefinition? = shown[player.uniqueId]?.sky

    fun choose(player: Player, id: String?): Boolean {
        if (id == null) {
            chosen.remove(player.uniqueId)
        } else {
            if (id != HIDDEN && definitions.none { it.id == id }) return false
            chosen[player.uniqueId] = id
        }
        update(player)
        return true
    }

    fun wanted(player: Player): SkyboxDefinition? {
        chosen[player.uniqueId]?.let { id -> return if (id == HIDDEN) null else definitions.firstOrNull { it.id == id } }
        val world = player.world
        val dimension = world.key.asString()
        val biome = player.location.block.biome.key.asString()
        return SkyboxDefinitions.best(definitions, dimension, world.name, biome, player::hasPermission)
    }

    private fun canShow(player: Player): Boolean = definitions.isNotEmpty() && pack.loadedEra(player)?.let { SkyboxShaders.supports(it) } == true

    private fun tick() {
        val now = Bukkit.getCurrentTick()
        for (player in Bukkit.getOnlinePlayers()) {
            if (!canShow(player)) {
                hide(player)
                continue
            }
            // The sky that fits only needs working out again when the player has moved to another world or biome, and now and then for permissions.
            if (now % 10 == 0) {
                val key = "${player.world.uid}:${player.location.block.biome.key.asString()}"
                if (location[player.uniqueId] != key || now % 100 == 0) {
                    location[player.uniqueId] = key
                    update(player)
                    continue
                }
            }
            refresh(player, now)
        }
    }

    private fun update(player: Player) {
        if (!canShow(player)) {
            hide(player)
            return
        }
        val want = wanted(player)
        val mine = shown[player.uniqueId]
        when {
            want == null -> hide(player)
            mine == null -> show(player, want)
            mine.sky.id != want.id -> {
                hide(player)
                show(player, want)
            }
            else -> refresh(player, Bukkit.getCurrentTick())
        }
    }

    private fun refresh(player: Player, now: Int) {
        val mine = shown[player.uniqueId] ?: return
        val color = colorOf(player)
        val size = sizeOf(player)
        val data = ArrayList<EntityData<*>>()
        if (color != mine.color) {
            mine.color = color
            data += itemData(mine.sky, color)
        }
        if (size != mine.size) {
            mine.size = size
            data += EntityData(SCALE, EntityDataTypes.VECTOR3F, scaleOf(size))
        }
        if (data.isNotEmpty()) PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerEntityMetadata(mine.entityId, data))
        if (now - mine.resentAt > 100) {
            mine.resentAt = now
            sendPassengers(player, mine)
        }
    }

    private fun show(player: Player, sky: SkyboxDefinition) {
        val mine = Shown(SpigotReflectionUtil.generateEntityId(), sky, sizeOf(player), colorOf(player), Bukkit.getCurrentTick())
        shown[player.uniqueId] = mine
        val players = PacketEvents.getAPI().playerManager
        val at = player.location
        val data = listOf(
            EntityData(FLAGS, EntityDataTypes.BYTE, 0),
            EntityData(SCALE, EntityDataTypes.VECTOR3F, scaleOf(mine.size)),
            // Full light, and no culling: the sky is far bigger than any box an entity could have.
            EntityData(BRIGHTNESS, EntityDataTypes.INT, 15 shl 4 or (15 shl 20)),
            EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, 1000f),
            itemData(sky, mine.color),
        )
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(player, WrapperPlayServerSpawnEntity(mine.entityId, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, PacketLocation(at.x, at.y, at.z, 0f, 0f), 0f, 0, Vector3d.zero()))
        players.sendPacket(player, WrapperPlayServerEntityMetadata(mine.entityId, data))
        sendPassengers(player, mine)
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    private fun hide(player: Player) {
        val mine = shown.remove(player.uniqueId) ?: return
        destroy(player, mine)
    }

    private fun destroy(player: Player, mine: Shown) {
        val players = PacketEvents.getAPI().playerManager
        players.sendPacket(player, WrapperPlayServerSetPassengers(player.entityId, player.passengers.map { it.entityId }.toIntArray()))
        players.sendPacket(player, WrapperPlayServerDestroyEntities(mine.entityId))
    }

    private fun sendPassengers(player: Player, mine: Shown) {
        val ids = player.passengers.map { it.entityId } + mine.entityId
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerSetPassengers(player.entityId, ids.toIntArray()))
    }

    private fun itemData(sky: SkyboxDefinition, color: Int): EntityData<*> {
        val item = com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(ItemTypes.PAPER)
            .amount(1)
            .component(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation("repack", "sky/${sky.id}")))
            .component(ComponentTypes.DYED_COLOR, ItemDyeColor(color, false))
            .build()
        return EntityData(ITEM, EntityDataTypes.ITEMSTACK, item)
    }

    /** What the item carries to the shader: the time of day in the red and green (0 to 23999 as two bytes), the height of the player in the blue. */
    private fun colorOf(player: Player): Int {
        val time = Math.floorMod(player.world.time, 24000L).toInt()
        val height = (((player.location.y + 64.0) / 384.0).coerceIn(0.0, 1.0) * 255.0).toInt()
        return (time / 256 shl 16) or (time % 256 shl 8) or height
    }

    /** A sky four times as wide as the player sees, inside out: half the width is twice the distance they see. */
    private fun sizeOf(player: Player): Int = maxOf(2, minOf(player.clientViewDistance, player.viewDistance)) * 16 * 4

    private fun scaleOf(size: Int): Vector3f = Vector3f(-size.toFloat(), -size.toFloat(), -size.toFloat())

    fun onPackLoaded(player: Player) = update(player)

    fun onPackLost(player: Player) {
        // Without the pack the model would show as a huge missing model, so the display goes.
        hide(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        shown.remove(event.player.uniqueId)
        chosen.remove(event.player.uniqueId)
        location.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        // The client threw the display away with the rest of the world: the next check puts a new one.
        shown.remove(event.player.uniqueId)
        location.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        shown.remove(event.player.uniqueId)
        location.remove(event.player.uniqueId)
    }

    companion object {
        const val HIDDEN = "none"

        private const val FLAGS = 0
        private const val SCALE = 12
        private const val BRIGHTNESS = 16
        private const val VIEW_RANGE = 17
        private const val ITEM = 23
    }
}
