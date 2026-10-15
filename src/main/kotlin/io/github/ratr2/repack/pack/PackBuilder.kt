package io.github.ratr2.repack.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PackBuilder {

    /** Oldest pack format this plugin writes: 1.21.4. */
    private const val MIN_FORMAT = 46

    /** Newest format with the same item model layout: 26.3 (97.1). */
    private const val MAX_FORMAT_MAJOR = 97
    private const val MAX_FORMAT_MINOR = 1

    /** The last format that still reads `supported_formats` instead of `min_format` / `max_format`: 1.21.7 and 1.21.8. */
    private const val LAST_RANGE_FORMAT = 64

    fun meta(description: String): ByteArray {
        val pack = JsonObject().apply {
            addProperty("pack_format", MIN_FORMAT)
            add("supported_formats", JsonObject().apply {
                addProperty("min_inclusive", MIN_FORMAT)
                addProperty("max_inclusive", LAST_RANGE_FORMAT)
            })
            addProperty("min_format", MIN_FORMAT)
            add("max_format", JsonArray().apply {
                add(MAX_FORMAT_MAJOR)
                add(MAX_FORMAT_MINOR)
            })
            addProperty("description", description)
        }
        return JsonObject().apply { add("pack", pack) }.toString().toByteArray(Charsets.UTF_8)
    }

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
