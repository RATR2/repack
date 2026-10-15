package io.github.ratr2.repack.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostRoutesTest {

    @Test
    fun `splits a source and a file`() {
        assertEquals("repack" to "0123abcd.zip", HostRoutes.parse("/repack/0123abcd.zip"))
        assertEquals("distortion" to "distortion-26.3-ab12.zip", HostRoutes.parse("/distortion/distortion-26.3-ab12.zip"))
    }

    @Test
    fun `rejects anything that is not exactly source and file`() {
        assertNull(HostRoutes.parse("/"))
        assertNull(HostRoutes.parse("/repack"))
        assertNull(HostRoutes.parse("/repack/a/b.zip"))
        assertNull(HostRoutes.parse("/repack/"))
    }

    @Test
    fun `rejects path tricks`() {
        assertNull(HostRoutes.parse("/repack/../secret"))
        assertNull(HostRoutes.parse("/../etc/passwd"))
        assertNull(HostRoutes.parse("/.hidden/file.zip"))
        assertNull(HostRoutes.parse("/repack/.hidden"))
        assertNull(HostRoutes.parse("/repack/a%2Fb.zip"))
        assertNull(HostRoutes.parse("/repack/a b.zip"))
    }
}
