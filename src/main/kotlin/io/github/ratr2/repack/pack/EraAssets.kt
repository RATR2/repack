package io.github.ratr2.repack.pack

import java.io.File
import java.util.jar.JarFile

/** The shader files of each era, embedded in the jar under `packs/<era id>/`. Their pack.mcmeta and pack.png are not used: the pack makes its own. */
object EraAssets {

    fun files(jar: File, era: PackEra, effects: TextEffects.Snippet? = null, textRules: TextRules.Snippet? = null): Map<String, ByteArray> {
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
        val textShaders = listOf("assets/minecraft/shaders/core/rendertype_text.vsh", "assets/minecraft/shaders/core/text.vsh")
        // The rules go in first: the effects then read the rule's real color, so a rule can name an effect trigger.
        if (textRules != null) {
            for (path in textShaders) {
                val shader = files[path] ?: continue
                files[path] = (TextRules.inject(String(shader, Charsets.UTF_8), textRules) ?: throw IllegalStateException("The text rules do not fit the ${era.id} text shader.")).toByteArray(Charsets.UTF_8)
            }
        }
        if (effects != null && TextEffects.supports(era)) {
            for (path in textShaders) {
                val shader = files[path] ?: continue
                files[path] = (TextEffects.inject(String(shader, Charsets.UTF_8), effects) ?: throw IllegalStateException("The r4tshader effects do not fit the ${era.id} text shader.")).toByteArray(Charsets.UTF_8)
            }
        }
        check(files.isNotEmpty()) { "No pack files under $prefix in the plugin jar" }
        return files
    }
}
