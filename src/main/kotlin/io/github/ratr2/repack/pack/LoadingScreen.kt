package io.github.ratr2.repack.pack

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock
import io.github.ratr2.repack.Repack
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.World
import org.bukkit.block.TileState
import org.bukkit.entity.Player
import java.util.UUID

/**
 * While a client reloads a resource pack, the blocks around the player look black to that player: every block in a cube around them,
 * air included, is shown as black concrete (packets only, the world is not touched) and put back to what it really is afterwards.
 * The two blocks the player stands in are left alone, so they are not inside a solid block.
 */
class LoadingScreen(private val plugin: Repack) {

    private class Shown(val world: World, val blocks: List<IntArray>, val tiles: List<IntArray>)

    private val shown = HashMap<UUID, Shown>()

    fun show(player: Player) {
        val radius = plugin.config.getInt("resource-pack.loading-radius", 8).coerceIn(1, MAX_RADIUS)
        val world = player.world
        val center = player.location
        val found = ArrayList<IntArray>()
        // Chests, signs and the like lose their contents on the client when they are replaced, so they are sent again afterwards.
        val tiles = ArrayList<IntArray>()
        for (x in center.blockX - radius..center.blockX + radius) {
            for (z in center.blockZ - radius..center.blockZ + radius) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                for (y in maxOf(world.minHeight, center.blockY - radius)..minOf(world.maxHeight - 1, center.blockY + radius)) {
                    if (x == center.blockX && z == center.blockZ && (y == center.blockY || y == center.blockY + 1)) continue
                    val block = world.getBlockAt(x, y, z)
                    found += intArrayOf(x, y, z)
                    if (!block.type.isAir && block.getState(false) is TileState) tiles += intArrayOf(x, y, z)
                }
            }
        }
        if (found.isEmpty()) return
        val black = StateTypes.BLACK_CONCRETE.createBlockState()
        send(player, found) { black }
        shown[player.uniqueId] = Shown(world, found, tiles)
    }

    fun hide(player: Player) {
        val before = shown.remove(player.uniqueId) ?: return
        restore(player, before)
        plugin.server.scheduler.runTaskLater(plugin, Runnable { restore(player, before) }, RESEND_DELAY_TICKS)
    }

    private fun restore(player: Player, before: Shown) {
        if (!player.isOnline || player.world != before.world) return
        send(player, before.blocks) { (x, y, z) ->
            SpigotConversionUtil.fromBukkitBlockData(before.world.getBlockAt(x, y, z).blockData)
        }
        for ((x, y, z) in before.tiles) {
            val block = before.world.getBlockAt(x, y, z)
            (block.getState(false) as? TileState)?.let { player.sendBlockUpdate(block.location, it) }
        }
    }

    fun forget(player: Player) {
        shown.remove(player.uniqueId)
    }

    private fun send(player: Player, blocks: List<IntArray>, stateOf: (IntArray) -> com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState) {
        try {
            for ((section, inSection) in blocks.groupBy { Triple(it[0] shr 4, it[1] shr 4, it[2] shr 4) }) {
                val encoded = inSection.map { EncodedBlock(stateOf(it), it[0], it[1], it[2]) }.toTypedArray()
                val packet = WrapperPlayServerMultiBlockChange(Vector3i(section.first, section.second, section.third), false, encoded)
                PacketEvents.getAPI().playerManager.sendPacket(player, packet)
            }
        } catch (e: Exception) {
            plugin.logger.warning("Could not send the loading screen blocks to ${player.name}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** The columns and the heights that stay as they are: everything the body touches, with a little room round it, and the layer of blocks it stands on. */
    class ClearArea(val x: IntRange, val y: IntRange, val z: IntRange)

    companion object {
        /** How far past the body a block still counts: a player at the very edge of a block can be nudged by the next one. */
        private const val MARGIN = 0.12

        /** The area to leave alone for a body in the box from ([minX], [minY], [minZ]) to ([maxX], [maxY], [maxZ]). */
        fun clearArea(minX: Double, maxX: Double, minY: Double, maxY: Double, minZ: Double, maxZ: Double) = ClearArea(
            Math.floor(minX - MARGIN).toInt()..Math.floor(maxX + MARGIN).toInt(),
            Math.floor(minY).toInt() - 1..Math.floor(maxY + MARGIN).toInt(),
            Math.floor(minZ - MARGIN).toInt()..Math.floor(maxZ + MARGIN).toInt(),
        )

        const val MAX_RADIUS = 16

        const val RESEND_DELAY_TICKS = 20L
    }
}

private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
private operator fun IntArray.component3() = this[2]
