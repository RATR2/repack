package io.github.ratr2.repack.pack

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackVariantTest {

    @Test
    fun `clients are matched to the pack that suits them`() {
        assertNull(PackVariant.forProtocol(768)) // 1.21.2, before the oldest supported release
        assertEquals(PackVariant.LEGACY, PackVariant.forProtocol(769)) // 1.21.4
        assertEquals(PackVariant.LEGACY, PackVariant.forProtocol(772)) // 1.21.7 and 1.21.8
        assertEquals(PackVariant.MODERN, PackVariant.forProtocol(773)) // 1.21.9, the first with the shelf display setting
        assertEquals(PackVariant.MODERN, PackVariant.forProtocol(777)) // 26.3
        assertEquals(PackVariant.MODERN, PackVariant.forProtocol(900)) // a release newer than this plugin knows
        assertEquals(769, PackVariant.oldestProtocol)
        assertTrue(!PackVariant.LEGACY.shelf && PackVariant.MODERN.shelf)
    }

    @Test
    fun `each version of the pack states the formats it works for`() {
        val legacy = JsonParser.parseString(String(PackBuilder.meta(PackVariant.LEGACY))).asJsonObject.getAsJsonObject("pack")
        assertEquals(46, legacy.get("pack_format").asInt)
        assertEquals(64, legacy.getAsJsonObject("supported_formats").get("max_inclusive").asInt)
        assertTrue(!legacy.has("min_format"))

        val modern = JsonParser.parseString(String(PackBuilder.meta(PackVariant.MODERN))).asJsonObject.getAsJsonObject("pack")
        assertEquals(69, modern.get("min_format").asInt)
        assertEquals(97, modern.getAsJsonArray("max_format")[0].asInt)
        assertTrue(!modern.has("pack_format"))
    }

    @Test
    fun `the description is two lines of text and does not use the old font characters`() {
        val description = JsonParser.parseString(String(PackBuilder.meta(PackVariant.MODERN))).asJsonObject
            .getAsJsonObject("pack").getAsJsonArray("description")
        assertEquals("Repack", description[0].asJsonObject.get("text").asString)
        assertTrue(description[1].asJsonObject.get("text").asString.startsWith("\n"))
        assertTrue(description.toString().none { it in '\uD800'..'\uDFFF' || it in ''..'' })
    }

    @Test
    fun `the icon is a real png`() {
        val icon = PackBuilder.icon()
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), icon.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `the store keeps each version apart and only publishes what was built`() {
        val folder = Files.createTempDirectory("repack-store").toFile()
        try {
            val store = PackStore(folder)
            assertTrue(!store.hasPublished)
            assertTrue(store.publish().isEmpty())

            val legacy = store.add(PackVariant.LEGACY, PackBuilder.zip(mapOf("a.txt" to "one".toByteArray())))
            val modern = store.add(PackVariant.MODERN, PackBuilder.zip(mapOf("a.txt" to "two".toByteArray())))
            assertTrue(legacy.sha1 != modern.sha1)
            assertTrue(!store.hasPublished, "building must not publish")

            assertEquals(mapOf(PackVariant.LEGACY to legacy, PackVariant.MODERN to modern), store.publish())
            assertTrue(store.hasPublished)

            val reloaded = PackStore(folder)
            assertEquals(legacy, reloaded.published[PackVariant.LEGACY])
            assertEquals(modern, reloaded.published[PackVariant.MODERN])
            ZipInputStream(reloaded.file(modern.sha1)!!.inputStream()).use { assertEquals("a.txt", it.nextEntry!!.name) }
            assertNull(reloaded.file("../etc/passwd"))
        } finally {
            folder.deleteRecursively()
        }
    }
}
