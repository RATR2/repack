package io.github.ratr2.repack.pack

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackStoreTest {

    private fun store(): Pair<PackStore, File> {
        val dir = Files.createTempDirectory("repack-store").toFile()
        return PackStore(dir) to dir
    }

    @Test
    fun `a pack file damaged by a crash is written again by the next build`() {
        val (store, dir) = store()
        val zip = ByteArray(1000) { it.toByte() }
        val entry = store.add(PackVariant.MODERN, zip)
        val file = File(dir, "packs/${entry.sha1}.zip")
        file.writeBytes(zip.copyOf(300))

        store.add(PackVariant.MODERN, zip)
        assertEquals(zip.size.toLong(), file.length())
    }

    @Test
    fun `no temporary files are left behind`() {
        val (store, dir) = store()
        store.add(PackVariant.MODERN, ByteArray(10))
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `packs nothing refers to are deleted after a day, current ones never`() {
        val (store, dir) = store()
        val old = store.add(PackVariant.MODERN, ByteArray(10) { 1 })
        val oldFile = File(dir, "packs/${old.sha1}.zip")
        store.add(PackVariant.MODERN, ByteArray(10) { 2 })
        assertTrue(oldFile.exists(), "a pack replaced a moment ago may still be downloading")

        oldFile.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
        val current = store.add(PackVariant.MODERN, ByteArray(10) { 3 })
        assertFalse(oldFile.exists())
        assertTrue(File(dir, "packs/${current.sha1}.zip").exists())
    }
}
