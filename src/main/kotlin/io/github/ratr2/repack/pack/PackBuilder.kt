package io.github.ratr2.repack.pack

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PackBuilder {

    /** 1.21.4. */
    private const val FIRST_FORMAT = 46

    /** 1.21.7 and 1.21.8: the last formats that read `supported_formats` instead of `min_format` and `max_format`. */
    private const val LAST_RANGE_FORMAT = 64

    /** 1.21.9: the first format that reads `min_format` and `max_format`. */
    private const val MODERN_FORMAT = 69

    /** 26.3 (97.1), the newest format. */
    private const val NEWEST_MAJOR = 97
    private const val NEWEST_MINOR = 1

    fun meta(era: PackEra): ByteArray {
        val pack = JsonObject()
        era.putFormat(pack)
        pack.add("description", description())
        return JsonObject().apply { add("pack", pack) }.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * One zip for every client version, which a plugin that sends a single pack to everybody can use. The base is the 1.21.4 to 1.21.8 pack;
     * overlays (pack.mcmeta `overlays`, 1.20.2 and newer) put the files of newer clients on top: first what differs in the 1.21.9 and newer
     * block files, then the shaders of each client version. Every range is written both the old way (`formats`) and the new one
     * (`min_format` and `max_format`, 1.21.9 and newer), because which one a client reads depends on its version.
     */
    fun universal(
        legacy: Map<String, ByteArray>,
        modern: Map<String, ByteArray>,
        common: Map<String, ByteArray>,
        eraFiles: Map<PackEra, Map<String, ByteArray>>,
    ): ByteArray {
        val files = HashMap<String, ByteArray>(legacy)
        files.putAll(common)
        val entries = JsonArray()
        val changed = modern.filter { (path, bytes) -> legacy[path]?.contentEquals(bytes) != true }
        if (changed.isNotEmpty()) {
            for ((path, bytes) in changed) files["$MODERN_OVERLAY/$path"] = bytes
            entries.add(overlay(MODERN_OVERLAY, MODERN_FORMAT, 0, NEWEST_MAJOR, NEWEST_MINOR))
        }
        for (era in PackEra.entries) {
            val shaders = eraFiles[era].orEmpty()
            if (shaders.isEmpty()) continue
            val directory = "era_" + era.id.replace('.', '_')
            for ((path, bytes) in shaders) files["$directory/$path"] = bytes
            entries.add(when (val f = era.format) {
                is PackEra.Format.Old -> overlay(directory, f.first, 0, f.last, 0)
                is PackEra.Format.New -> overlay(directory, f.minMajor, f.minMinor, f.maxMajor, f.maxMinor)
            })
        }
        val pack = JsonObject()
        // For clients up to 1.21.8: pack_format and supported_formats. For 1.21.9 and newer: min_format and max_format.
        pack.addProperty("pack_format", FIRST_FORMAT)
        pack.add("supported_formats", JsonObject().apply {
            addProperty("min_inclusive", FIRST_FORMAT)
            addProperty("max_inclusive", LAST_RANGE_FORMAT)
        })
        pack.addProperty("min_format", FIRST_FORMAT)
        pack.add("max_format", JsonArray().apply { add(NEWEST_MAJOR); add(NEWEST_MINOR) })
        pack.add("description", description())
        files["pack.mcmeta"] = JsonObject().apply {
            add("pack", pack)
            if (entries.size() > 0) add("overlays", JsonObject().apply { add("entries", entries) })
        }.toString().toByteArray(Charsets.UTF_8)
        files["pack.png"] = icon()
        return zip(files)
    }

    private const val MODERN_OVERLAY = "modern"

    /** An overlay for formats minMajor.minMinor to maxMajor.maxMinor; a minor of 0 on the old side means the whole major. */
    private fun overlay(directory: String, minMajor: Int, minMinor: Int, maxMajor: Int, maxMinor: Int) = JsonObject().apply {
        addProperty("directory", directory)
        add("formats", JsonObject().apply {
            addProperty("min_inclusive", minMajor)
            addProperty("max_inclusive", maxMajor)
        })
        if (minMajor >= MODERN_FORMAT) {
            add("min_format", JsonArray().apply { add(minMajor); add(minMinor) })
            add("max_format", JsonArray().apply { add(maxMajor); add(maxMinor) })
        } else {
            addProperty("min_format", minMajor)
            addProperty("max_format", maxMajor)
        }
    }

    private fun description() = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("text", "Repack")
            addProperty("color", "gold")
        })
        add(JsonObject().apply {
            addProperty("text", "\nCustom blocks and effects")
            addProperty("color", "gray")
        })
    }

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
