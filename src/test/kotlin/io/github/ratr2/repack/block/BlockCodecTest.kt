package io.github.ratr2.repack.block

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockCodecTest {

    @Test
    fun `round trips blocks including negative coordinates`() {
        val blocks = mapOf(
            BlockPos(-17, 64, 3) to "table",
            BlockPos(5, -60, -200) to "lamp_post",
            BlockPos(0, 319, 0) to "a-b",
        )
        assertEquals(blocks, BlockCodec.decode(BlockCodec.encode(blocks)))
    }

    @Test
    fun `encoding is stable regardless of insertion order`() {
        val a = linkedMapOf(BlockPos(2, 0, 0) to "x", BlockPos(1, 0, 0) to "y")
        val b = linkedMapOf(BlockPos(1, 0, 0) to "y", BlockPos(2, 0, 0) to "x")
        assertEquals(BlockCodec.encode(a), BlockCodec.encode(b))
    }

    @Test
    fun `skips malformed lines and keeps the rest`() {
        val decoded = BlockCodec.decode("1 2 3 good\nnot a line\n4 five 6 bad\n7 8 9\n10 11 12 also_good\n")
        assertEquals(mapOf(BlockPos(1, 2, 3) to "good", BlockPos(10, 11, 12) to "also_good"), decoded)
    }

    @Test
    fun `empty text decodes to no blocks`() {
        assertTrue(BlockCodec.decode("").isEmpty())
    }
}
