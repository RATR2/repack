package io.github.ratr2.repack.pack

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes the built packs into a folder for other plugins to pick up (ItemsAdder, CraftEngine and the like can add an external
 * zip to their pack, as with BetterModel). `repack.zip` is one era's pack (the newest by default), `repack-<era>.zip` each
 * era's, and `repack-all.zip` serves every client version with overlays. Names never change between builds, so that plugin's
 * setting doesn't need to.
 */
object PackExport {

    const val MAIN = "repack.zip"

    const val ALL = "repack-all.zip"

    fun eraOf(name: String): PackEra? = PackEra.entries.firstOrNull { it.id == name.trim() }

    fun write(folder: File, zips: Map<PackEra, ByteArray>, main: PackEra, all: ByteArray? = null): String {
        folder.mkdirs()
        all?.let { put(File(folder, ALL), it) }
        zips[main]?.let { put(File(folder, MAIN), it) }
        for ((era, zip) in zips) put(File(folder, "repack-${era.id}.zip"), zip)
        return folder.path
    }

    private fun put(target: File, bytes: ByteArray) {
        if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) return
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
