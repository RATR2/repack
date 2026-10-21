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
import com.github.retrooper.packetevents.util.Quaternion4f
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.ratr2.repack.model.BoneDisplay
import io.github.ratr2.repack.model.ItemModelGenerator
import io.github.ratr2.repack.model.Rig
import io.github.ratr2.repack.model.Vec3
import io.github.ratr2.repack.text.Colors
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import org.bukkit.entity.Player
import org.joml.Quaternionf
import java.util.UUID

/** How a rigged model is shown as a whole: moved and scaled as a block's or an entity's options say. offset is in blocks, in the model's own frame. */
class RigLook(
    val offset: Vec3,
    val scale: Vec3,
    val brightness: Int?,
    val viewRange: Float,
    val context: Byte,
    val name: String,
    val teleportDuration: Int? = null,
)

object RigDisplays {

    /** Ticks an animation step takes to play out on the client, so that steps sent every other tick blend into each other. */
    const val STEP_TICKS = 2

    private const val FLAGS = 0
    private const val INVISIBLE: Byte = 0x20
    private const val START_INTERPOLATION = 8
    private const val TRANSFORM_DURATION = 9
    private const val TELEPORT_DURATION = 10
    private const val TRANSLATION = 11
    private const val SCALE = 12
    private const val LEFT_ROTATION = 13
    private const val BRIGHTNESS = 16
    private const val VIEW_RANGE = 17
    private const val ITEM = 23
    private const val ITEM_CONTEXT = 24

    fun newIds(rig: Rig): IntArray = IntArray(rig.bones.size) { if (rig.bones[it].hasModel) SpigotReflectionUtil.generateEntityId() else -1 }

    fun spawn(player: Player, model: String, rig: Rig, ids: IntArray, location: PacketLocation, look: RigLook, displays: List<BoneDisplay>) {
        val players = PacketEvents.getAPI().playerManager
        // One bundle, so the client applies every piece in the same tick and never draws half of a model.
        players.sendPacket(player, WrapperPlayServerBundle())
        for ((index, id) in ids.withIndex()) {
            if (id < 0) continue
            players.sendPacket(player, WrapperPlayServerSpawnEntity(id, UUID.randomUUID(), EntityTypes.ITEM_DISPLAY, location, location.yaw, 0, Vector3d.zero()))
            players.sendPacket(player, WrapperPlayServerEntityMetadata(id, spawnData(model, index, look, displays[index])))
        }
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    fun update(viewers: Collection<Player>, ids: IntArray, look: RigLook, displays: List<BoneDisplay>, before: List<BoneDisplay>?) {
        if (viewers.isEmpty()) return
        val players = PacketEvents.getAPI().playerManager
        for ((index, id) in ids.withIndex()) {
            if (id < 0) continue
            val now = displays[index]
            if (before != null && same(before[index], now)) continue
            val data = ArrayList<EntityData<*>>()
            data += EntityData(START_INTERPOLATION, EntityDataTypes.INT, 0)
            data += EntityData(TRANSFORM_DURATION, EntityDataTypes.INT, STEP_TICKS)
            data += transform(look, now)
            val packet = WrapperPlayServerEntityMetadata(id, data)
            for (viewer in viewers) players.sendPacket(viewer, packet)
        }
    }

    fun teleport(player: Player, ids: IntArray, location: PacketLocation) {
        val players = PacketEvents.getAPI().playerManager
        for (id in ids) if (id >= 0) players.sendPacket(player, WrapperPlayServerEntityTeleport(id, location, false))
    }

    fun destroy(player: Player, ids: IntArray) {
        val live = ids.filter { it >= 0 }
        if (live.isEmpty()) return
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerDestroyEntities(*live.toIntArray()))
    }

    private fun same(a: BoneDisplay, b: BoneDisplay) =
        a.translation == b.translation && a.scale == b.scale && a.rotation.equals(b.rotation, 1e-6f)

    private fun transform(look: RigLook, display: BoneDisplay): List<EntityData<*>> {
        val translation = Vector3f(
            (look.offset.x + look.scale.x * display.translation.x).toFloat(),
            (look.offset.y + look.scale.y * display.translation.y).toFloat(),
            (look.offset.z + look.scale.z * display.translation.z).toFloat(),
        )
        val scale = Vector3f((look.scale.x * display.scale.x).toFloat(), (look.scale.y * display.scale.y).toFloat(), (look.scale.z * display.scale.z).toFloat())
        // The game turns an item display's model half a turn before drawing it, so each bone's model is turned back; otherwise
        // it would face south instead of north and animations would run toward the wrong side.
        val r = Quaternionf(display.rotation).mul(Quaternionf().rotationY(Math.PI.toFloat()))
        return listOf(
            EntityData(TRANSLATION, EntityDataTypes.VECTOR3F, translation),
            EntityData(SCALE, EntityDataTypes.VECTOR3F, scale),
            EntityData(LEFT_ROTATION, EntityDataTypes.QUATERNION, Quaternion4f(r.x, r.y, r.z, r.w)),
        )
    }

    private fun spawnData(model: String, bone: Int, look: RigLook, display: BoneDisplay): List<EntityData<*>> {
        val item = com.github.retrooper.packetevents.protocol.item.ItemStack.builder()
            .type(ItemTypes.PAPER)
            .amount(1)
            .component(ComponentTypes.ITEM_MODEL, ItemModel(ResourceLocation(ItemModelGenerator.NAMESPACE, "$model/b$bone")))
            // Jade and similar mods show the item's name for an item display, which would be "Paper".
            .component(ComponentTypes.ITEM_NAME, Colors.parse(look.name))
            .build()
        val data = ArrayList<EntityData<*>>()
        data += EntityData(FLAGS, EntityDataTypes.BYTE, INVISIBLE)
        look.teleportDuration?.let { data += EntityData(TELEPORT_DURATION, EntityDataTypes.INT, it) }
        data += transform(look, display)
        look.brightness?.let { data += EntityData(BRIGHTNESS, EntityDataTypes.INT, it shl 4 or (it shl 20)) }
        if (look.viewRange != 1f) data += EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, look.viewRange)
        data += EntityData(ITEM, EntityDataTypes.ITEMSTACK, item)
        if (look.context != 0.toByte()) data += EntityData(ITEM_CONTEXT, EntityDataTypes.BYTE, look.context)
        return data
    }
}
