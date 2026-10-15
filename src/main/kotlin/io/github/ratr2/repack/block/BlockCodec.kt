package io.github.ratr2.repack.block

data class BlockPos(val x: Int, val y: Int, val z: Int)

/** One placed custom block: which type it is and which way it faces, in degrees (0, 90, 180 or 270). */
data class PlacedBlock(val id: String, val yaw: Int = 0)

/**
 * The text form of one chunk's custom blocks, stored in the chunk's persistent data so it is saved, copied and backed up
 * with the world. One block per line: `x y z id yaw`. Ids contain only a-z, 0-9, `_` and `-`, so they never hold a space.
 * Lines written before rotation existed have no yaw and read as 0.
 */
object BlockCodec {

    fun encode(blocks: Map<BlockPos, PlacedBlock>): String =
        blocks.entries.sortedWith(compareBy({ it.key.x }, { it.key.y }, { it.key.z }))
            .joinToString("\n") { (pos, block) -> "${pos.x} ${pos.y} ${pos.z} ${block.id} ${block.yaw}" }

    /** Skips malformed lines instead of failing, so one bad line cannot lose the rest of the chunk. */
    fun decode(text: String): MutableMap<BlockPos, PlacedBlock> {
        val result = HashMap<BlockPos, PlacedBlock>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size != 4 && parts.size != 5) continue
            val x = parts[0].toIntOrNull() ?: continue
            val y = parts[1].toIntOrNull() ?: continue
            val z = parts[2].toIntOrNull() ?: continue
            val yaw = if (parts.size == 5) parts[4].toIntOrNull() ?: continue else 0
            result[BlockPos(x, y, z)] = PlacedBlock(parts[3], Math.floorMod(yaw, 360))
        }
        return result
    }
}
