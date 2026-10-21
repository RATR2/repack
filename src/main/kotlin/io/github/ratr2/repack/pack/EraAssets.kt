package io.github.ratr2.repack.pack

import java.io.File
import java.util.jar.JarFile

/** The shader files of each era, embedded in the jar under `packs/<era id>/`. Their pack.mcmeta and pack.png are not used: the pack makes its own. */
object EraAssets {

    fun files(jar: File, era: PackEra): Map<String, ByteArray> {
        val prefix = "packs/${era.id}/"
        val files = HashMap<String, ByteArray>()
        JarFile(jar).use { jarFile ->
            for (entry in jarFile.entries()) {
                if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                val path = entry.name.removePrefix(prefix)
                if (path == "pack.mcmeta" || path == "pack.png") continue
                files[path] = jarFile.getInputStream(entry).use { it.readBytes() }
            }
        }
        check(files.isNotEmpty()) { "No pack files under $prefix in the plugin jar" }
        return files
    }
}
