package io.github.ratr2.repack.block

data class BlockPos(val x: Int, val y: Int, val z: Int)

/**
 * The text form of one chunk's custom blocks, stored in the chunk's persistent data so it is saved, copied and backed up
 * with the world. One block per line: `x y z id`. Ids contain only a-z, 0-9, `_` and `-`, so they never hold a space.
 */
object BlockCodec {

    fun encode(blocks: Map<BlockPos, String>): String =
        blocks.entries.sortedWith(compareBy({ it.key.x }, { it.key.y }, { it.key.z }))
            .joinToString("\n") { (pos, id) -> "${pos.x} ${pos.y} ${pos.z} $id" }

    /** Skips malformed lines instead of failing, so one bad line cannot lose the rest of the chunk. */
    fun decode(text: String): MutableMap<BlockPos, String> {
        val result = HashMap<BlockPos, String>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(' ')
            if (parts.size != 4) continue
            val x = parts[0].toIntOrNull() ?: continue
            val y = parts[1].toIntOrNull() ?: continue
            val z = parts[2].toIntOrNull() ?: continue
            result[BlockPos(x, y, z)] = parts[3]
        }
        return result
    }
}
