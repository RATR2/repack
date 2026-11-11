package io.github.ratr2.repack.distortion

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.protocol.world.Location as PacketLocation
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.util.Vector3f
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import io.github.retrooper.packetevents.util.SpigotReflectionUtil
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The text display only one player sees, kept in front of their eyes, that the pack's text shader turns into data pixels in the corner of
 * the frame: the screen shake and the impact frame both are one. The strength comes from the glyph size, the rest from the text color, and
 * the text_opacity says which kind it is. The slot (the display's block light, 0 to 15) is the row of its data, so two markers of one
 * player need two slots. Also counts who needs the 26.3 post effect, which both effects share.
 */
internal object ScreenMarker {

    val POST_EFFECT: Key = Key.key("distortion", "shake")

    /** The text_opacity the pack's text shader takes for a shake and for an impact frame (the ripples use 123). */
    const val SHAKE_OPACITY: Byte = 122
    const val IMPACT_OPACITY: Byte = 121

    const val SHAKE_SLOT = 0
    const val IMPACT_SLOT = 8

    fun newId(): Int = SpigotReflectionUtil.generateEntityId()

    fun spawn(player: Player, entityId: Int, opacity: Byte, slot: Int, color: TextColor, strength: Double) {
        val data = listOf(
            EntityData(FLAGS, EntityDataTypes.BYTE, 0),
            EntityData(INTERPOLATION_START, EntityDataTypes.INT, 0),
            EntityData(TRANSFORM_DURATION, EntityDataTypes.INT, 0),
            EntityData(TELEPORT_DURATION, EntityDataTypes.INT, 1),
            // Three blocks ahead of the camera, whichever way it looks: a camera facing display's own z axis points at the camera.
            EntityData(TRANSLATION, EntityDataTypes.VECTOR3F, Vector3f(0f, 0f, -3f)),
            EntityData(SCALE, EntityDataTypes.VECTOR3F, scaleOf(strength)),
            EntityData(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
            // The brightness override is block light << 4 and sky light << 20; the slot is the block light.
            EntityData(BRIGHTNESS, EntityDataTypes.INT, slot.coerceIn(0, 15) shl 4),
            EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, 4f),
            EntityData(WIDTH, EntityDataTypes.FLOAT, BOX),
            EntityData(HEIGHT, EntityDataTypes.FLOAT, BOX),
            EntityData(TEXT, EntityDataTypes.ADV_COMPONENT, Component.text(GLYPH, color)),
            EntityData(BACKGROUND, EntityDataTypes.INT, 0),
            EntityData(TEXT_OPACITY, EntityDataTypes.BYTE, opacity),
            EntityData(TEXT_FLAGS, EntityDataTypes.BYTE, 0),
        )
        val players = PacketEvents.getAPI().playerManager
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(player, WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.TEXT_DISPLAY, locationOf(player), 0f, 0, Vector3d.zero()))
        players.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, data))
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    /**
     * A marker at a point in the world, like a ripple's: the pack reads the point on the screen from where it is, and from its glyph a width and a height, which
     * are [scaleX] and [scaleY] of the glyph (the pack decides what they mean). The block and sky light are [block] and [sky], 0 to 15 each. It is fixed in the
     * world, so it is not kept in front of the eyes. Its box is big so that it is still drawn when the point is a little off the screen.
     */
    fun spawnAt(player: Player, entityId: Int, location: Location, opacity: Byte, block: Int, sky: Int, color: TextColor, scaleX: Float, scaleY: Float) {
        val data = listOf(
            EntityData(FLAGS, EntityDataTypes.BYTE, 0),
            EntityData(INTERPOLATION_START, EntityDataTypes.INT, 0),
            EntityData(TRANSFORM_DURATION, EntityDataTypes.INT, 0),
            EntityData(TELEPORT_DURATION, EntityDataTypes.INT, 0),
            EntityData(SCALE, EntityDataTypes.VECTOR3F, Vector3f(scaleX, scaleY, SCALE_UNIT)),
            EntityData(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_FIXED),
            EntityData(BRIGHTNESS, EntityDataTypes.INT, (block.coerceIn(0, 15) shl 4) or (sky.coerceIn(0, 15) shl 20)),
            EntityData(VIEW_RANGE, EntityDataTypes.FLOAT, 4f),
            EntityData(WIDTH, EntityDataTypes.FLOAT, WORLD_BOX),
            EntityData(HEIGHT, EntityDataTypes.FLOAT, WORLD_BOX),
            EntityData(TEXT, EntityDataTypes.ADV_COMPONENT, Component.text(GLYPH, color)),
            EntityData(BACKGROUND, EntityDataTypes.INT, 0),
            EntityData(TEXT_OPACITY, EntityDataTypes.BYTE, opacity),
            EntityData(TEXT_FLAGS, EntityDataTypes.BYTE, 0),
        )
        val players = PacketEvents.getAPI().playerManager
        players.sendPacket(player, WrapperPlayServerBundle())
        players.sendPacket(player, WrapperPlayServerSpawnEntity(entityId, UUID.randomUUID(), EntityTypes.TEXT_DISPLAY, PacketLocation(location.x, location.y, location.z, 0f, 0f), 0f, 0, Vector3d.zero()))
        players.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, data))
        players.sendPacket(player, WrapperPlayServerBundle())
    }

    /** Keeps the marker in front of the eyes. */
    fun follow(player: Player, entityId: Int) {
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerEntityTeleport(entityId, locationOf(player), false))
    }

    fun setStrength(player: Player, entityId: Int, strength: Double) {
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerEntityMetadata(entityId, listOf(EntityData(SCALE, EntityDataTypes.VECTOR3F, scaleOf(strength)))))
    }

    fun destroy(player: Player, entityId: Int) {
        PacketEvents.getAPI().playerManager.sendPacket(player, WrapperPlayServerDestroyEntities(entityId))
    }

    /** Half a block under the eyes: the culling box then holds the camera, so the display is never left out of the frame. */
    private fun locationOf(player: Player): PacketLocation {
        val eyes = player.eyeLocation
        return PacketLocation(eyes.x, eyes.y - 0.5, eyes.z, 0f, 0f)
    }

    private fun scaleOf(strength: Double) = Vector3f((strength * SCALE_UNIT).toFloat(), SCALE_UNIT, 1f)

    // From 26.3 the pass is a post effect added to a player only while something needs it, by whichever effect starts first and until the last one ends.
    private val posts = HashMap<UUID, Int>()

    fun acquirePost(player: Player) {
        val count = posts.merge(player.uniqueId, 1, Int::plus)
        if (count == 1) PostEffectsCompat.add(player, POST_EFFECT)
    }

    fun releasePost(player: Player) {
        val count = posts[player.uniqueId] ?: return
        if (count <= 1) {
            posts.remove(player.uniqueId)
            PostEffectsCompat.remove(player, POST_EFFECT)
        } else {
            posts[player.uniqueId] = count - 1
        }
    }

    /** A player who left or changed world no longer has the effect on their screen. */
    fun forgetPost(player: Player) {
        if (posts.remove(player.uniqueId) != null) PostEffectsCompat.remove(player, POST_EFFECT)
    }

    fun forgetPostQuietly(player: Player) {
        posts.remove(player.uniqueId)
    }

    // The marker glyph the text shader recognizes.
    private const val GLYPH = "􃗏"
    /** The scale of a marker at strength 1: the shake keeps its glyph tiny; an impact frame focus point sizes its own glyph, see [spawnAt]. */
    const val SCALE_UNIT = 0.01f
    private const val BOX = 8f
    private const val BILLBOARD_CENTER: Byte = 3
    private const val BILLBOARD_FIXED: Byte = 0
    private const val WORLD_BOX = 64f

    private const val FLAGS = 0
    private const val INTERPOLATION_START = 8
    private const val TRANSFORM_DURATION = 9
    private const val TELEPORT_DURATION = 10
    private const val TRANSLATION = 11
    private const val SCALE = 12
    private const val BILLBOARD = 15
    private const val BRIGHTNESS = 16
    private const val VIEW_RANGE = 17
    private const val WIDTH = 20
    private const val HEIGHT = 21
    private const val TEXT = 23
    private const val BACKGROUND = 25
    private const val TEXT_OPACITY = 26
    private const val TEXT_FLAGS = 27
}
