package io.github.ratr2.repack.pack

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange.EncodedBlock
import io.github.ratr2.repack.Repack
import io.github.retrooper.packetevents.util.SpigotConversionUtil
import org.bukkit.World
import org.bukkit.entity.Player
import java.util.UUID

/**
 * While a client reloads a resource pack, the blocks around the player look black to that player: every solid block in a cube around
 * them is shown as black concrete (packets only, the world is not touched) and put back to what it really is afterwards.
 */
class LoadingScreen(private val plugin: Repack) {

    private class Shown(val world: World, val blocks: List<IntArray>)

    private val shown = HashMap<UUID, Shown>()

    fun show(player: Player) {
        val radius = plugin.config.getInt("resource-pack.loading-radius", 8).coerceIn(1, MAX_RADIUS)
        val world = player.world
        val center = player.location
        val found = ArrayList<IntArray>()
        for (x in center.blockX - radius..center.blockX + radius) {
            for (z in center.blockZ - radius..center.blockZ + radius) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                for (y in maxOf(world.minHeight, center.blockY - radius)..minOf(world.maxHeight - 1, center.blockY + radius)) {
                    if (!world.getBlockAt(x, y, z).type.isAir) found += intArrayOf(x, y, z)
                }
            }
        }
        if (found.isEmpty()) return
        val black = StateTypes.BLACK_CONCRETE.createBlockState()
        send(player, found) { black }
        shown[player.uniqueId] = Shown(world, found)
    }

    /** Puts the real blocks back for the player, as they are now. */
    fun hide(player: Player) {
        val before = shown.remove(player.uniqueId) ?: return
        if (!player.isOnline || player.world != before.world) return
        send(player, before.blocks) { (x, y, z) ->
            SpigotConversionUtil.fromBukkitBlockData(before.world.getBlockAt(x, y, z).blockData)
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

    private companion object {
        /** A cube of 33 blocks on a side, a few thousand blocks at most; more would only slow the server down. */
        const val MAX_RADIUS = 16
    }
}

private operator fun IntArray.component1() = this[0]
private operator fun IntArray.component2() = this[1]
private operator fun IntArray.component3() = this[2]
