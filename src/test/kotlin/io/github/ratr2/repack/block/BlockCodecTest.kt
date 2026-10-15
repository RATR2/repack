package io.github.ratr2.repack.block

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockCodecTest {

    @Test
    fun `round trips blocks including negative coordinates and rotation`() {
        val blocks = mapOf(
            BlockPos(-17, 64, 3) to PlacedBlock("table", 90),
            BlockPos(5, -60, -200) to PlacedBlock("lamp_post", 0),
            BlockPos(0, 319, 0) to PlacedBlock("a-b", 270),
        )
        assertEquals(blocks, BlockCodec.decode(BlockCodec.encode(blocks)))
    }

    @Test
    fun `encoding is stable regardless of insertion order`() {
        val a = linkedMapOf(BlockPos(2, 0, 0) to PlacedBlock("x"), BlockPos(1, 0, 0) to PlacedBlock("y"))
        val b = linkedMapOf(BlockPos(1, 0, 0) to PlacedBlock("y"), BlockPos(2, 0, 0) to PlacedBlock("x"))
        assertEquals(BlockCodec.encode(a), BlockCodec.encode(b))
    }

    @Test
    fun `lines without a yaw read as facing 0`() {
        assertEquals(mapOf(BlockPos(1, 2, 3) to PlacedBlock("old", 0)), BlockCodec.decode("1 2 3 old"))
    }

    @Test
    fun `skips malformed lines and keeps the rest`() {
        val decoded = BlockCodec.decode("1 2 3 good 90\nnot a line\n4 five 6 bad\n7 8 9\n10 11 12 also_good\n1 1 1 x notanumber")
        assertEquals(
            mapOf(BlockPos(1, 2, 3) to PlacedBlock("good", 90), BlockPos(10, 11, 12) to PlacedBlock("also_good", 0)),
            decoded,
        )
    }

    @Test
    fun `negative yaw is wrapped into 0 to 359`() {
        assertEquals(270, BlockCodec.decode("0 0 0 a -90").getValue(BlockPos(0, 0, 0)).yaw)
    }

    @Test
    fun `empty text decodes to no blocks`() {
        assertTrue(BlockCodec.decode("").isEmpty())
    }
}
