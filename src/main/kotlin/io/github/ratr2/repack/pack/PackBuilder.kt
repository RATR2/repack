package io.github.ratr2.repack.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Turns a set of asset files into the pack zip. The zip is byte-for-byte stable, so equal content gives an equal hash. */
object PackBuilder {

    /** 1.21.4. */
    private const val FIRST_FORMAT = 46

    /** 1.21.7 and 1.21.8: the last formats that read `supported_formats` instead of `min_format` and `max_format`. */
    private const val LAST_RANGE_FORMAT = 64

    /** 1.21.9: the first format that reads `min_format` and `max_format`. */
    private const val MODERN_FORMAT = 69

    /** 26.3 (97.1), the newest format with the same item model layout. */
    private const val NEWEST_MAJOR = 97
    private const val NEWEST_MINOR = 1

    fun meta(variant: PackVariant): ByteArray {
        val pack = JsonObject()
        when (variant) {
            PackVariant.LEGACY -> {
                pack.addProperty("pack_format", FIRST_FORMAT)
                pack.add("supported_formats", JsonObject().apply {
                    addProperty("min_inclusive", FIRST_FORMAT)
                    addProperty("max_inclusive", LAST_RANGE_FORMAT)
                })
            }
            PackVariant.MODERN -> {
                pack.addProperty("min_format", MODERN_FORMAT)
                pack.add("max_format", JsonArray().apply {
                    add(NEWEST_MAJOR)
                    add(NEWEST_MINOR)
                })
            }
        }
        pack.add("description", description())
        return JsonObject().apply { add("pack", pack) }.toString().toByteArray(Charsets.UTF_8)
    }

    /** Two lines, as the resource pack screen shows them. */
    private fun description() = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("text", "Repack")
            addProperty("color", "gold")
        })
        add(JsonObject().apply {
            addProperty("text", "\nCustom blocks from Blockbench models")
            addProperty("color", "gray")
        })
    }

    /** The pack icon bundled in the plugin jar. */
    fun icon(): ByteArray = PackBuilder::class.java.getResourceAsStream("/pack.png")!!.use { it.readBytes() }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in files.toSortedMap()) {
                zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
