package io.github.ratr2.repack.dev

import io.github.ratr2.repack.block.BukkitMaterials
import io.github.ratr2.repack.block.MaterialRules
import io.github.ratr2.repack.distortion.ImpactScenes
import io.github.ratr2.repack.item.ItemDefinitions
import io.github.ratr2.repack.menu.GuiLoader
import io.github.ratr2.repack.model.BbParser
import io.github.ratr2.repack.sky.SkyboxDefinitions
import org.bukkit.configuration.file.YamlConfiguration
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * Checks an uploaded file against the one kind its link takes, with the same code that loads the file from its folder, so what is accepted here loads there.
 * Returns what is wrong with it, nothing when it is fine. Nothing is written: a file that reads other files (an item that names a model, a GUI that extends
 * another) is checked in a copy of the folder.
 */
object UploadValidation {

    private const val MAX_GUI_PICTURE_SIDE = 4096

    /** [existing] is the folder the file would go into, [models] the models folder (an item names one). */
    fun check(kind: UploadKind, name: String, bytes: ByteArray, existing: File, models: File, materials: MaterialRules = BukkitMaterials): List<String> {
        if (bytes.isEmpty()) return listOf("The file is empty.")
        return when (kind) {
            UploadKind.MODEL -> modelProblems(name, bytes)
            UploadKind.SKYBOX_SHADER -> text(bytes)?.let { code -> listOfNotNull(SkyboxDefinitions.shaderProblem(code)) } ?: notText()
            UploadKind.SKYBOX_IMAGE -> picture(bytes) { w, h -> SkyboxDefinitions.pictureProblem(w, h) }
            UploadKind.GUI_PICTURE -> picture(bytes) { w, h -> if (w > MAX_GUI_PICTURE_SIDE || h > MAX_GUI_PICTURE_SIDE) "it is $w by $h: at most $MAX_GUI_PICTURE_SIDE on each side." else null }
            UploadKind.IMPACT, UploadKind.SKYBOX, UploadKind.GUI, UploadKind.ITEM -> yaml(kind, name, bytes, existing, models, materials)
        }
    }

    /** A model is read, and then converted the way a build does: the converter refuses models the parser takes (a bone reaching too far), and one it refuses stops every build. */
    private fun modelProblems(name: String, bytes: ByteArray): List<String> {
        val code = text(bytes) ?: return notText()
        try {
            BbParser.parse(name, code)
        } catch (e: Exception) {
            return listOf("Not a Blockbench model: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
        }
        val temp = Files.createTempDirectory("repack-upload").toFile()
        return try {
            File(temp, "$name.bbmodel").writeBytes(bytes)
            io.github.ratr2.repack.model.ModelLibrary.build(temp).errors
        } finally {
            temp.deleteRecursively()
        }
    }

    /** What is wrong with a YAML text that makes it unsafe to load (too many aliases, recursion, too big), or null. Does not build the values, only reads the structure. */
    fun safeYaml(code: String): String? {
        val options = org.yaml.snakeyaml.LoaderOptions().apply {
            maxAliasesForCollections = MAX_ALIASES
            codePointLimit = MAX_YAML_CODE_POINTS
            nestingDepthLimit = MAX_YAML_DEPTH
        }
        return try {
            org.yaml.snakeyaml.Yaml(options).compose(java.io.StringReader(code))
            null
        } catch (e: org.yaml.snakeyaml.error.MarkedYAMLException) {
            // A mistake in the text, not a limit: the real load below says what it is.
            null
        } catch (e: org.yaml.snakeyaml.error.YAMLException) {
            "The YAML is too big, too deeply nested or uses too many aliases (&anchors and *references): ${e.message?.lineSequence()?.firstOrNull()}"
        } catch (e: RuntimeException) {
            "The YAML cannot be read: ${e.message?.lineSequence()?.firstOrNull()}"
        }
    }

    private const val MAX_ALIASES = 20
    private const val MAX_YAML_CODE_POINTS = 512 * 1024
    private const val MAX_YAML_DEPTH = 50

    private fun notText() = listOf("The file is not text (UTF-8).")

    /** The text of a file, or null when it is not valid UTF-8. A byte order mark is dropped. */
    private fun text(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
    } catch (e: CharacterCodingException) {
        null
    }

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private fun picture(bytes: ByteArray, sizeProblem: (Int, Int) -> String?): List<String> {
        if (bytes.size < PNG.size || !bytes.copyOf(PNG.size).contentEquals(PNG)) return listOf("Not a PNG picture.")
        val size = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return listOf("Not a PNG picture.")
                try {
                    reader.setInput(input)
                    reader.getWidth(0) to reader.getHeight(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: Exception) {
            return listOf("The picture cannot be read: ${e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName}")
        }
        return listOfNotNull(sizeProblem(size.first, size.second)?.let { "The picture: $it" })
    }

    private fun yaml(kind: UploadKind, name: String, bytes: ByteArray, existing: File, models: File, materials: MaterialRules): List<String> {
        val code = text(bytes) ?: return notText()
        // Before the real load, with limits of its own: a small file can be written (anchors that refer to anchors, "billion laughs") to expand into gigabytes.
        safeYaml(code)?.let { return listOf(it) }
        try {
            YamlConfiguration().loadFromString(code)
        } catch (e: org.bukkit.configuration.InvalidConfigurationException) {
            return listOf("Not valid YAML: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val temp = Files.createTempDirectory("repack-upload").toFile()
        try {
            // The folder as it is, with the new file in place of one of the same name.
            existing.listFiles { f -> f.isFile && f.name != "$name.${kind.extension}" }.orEmpty().forEach { copy(it, File(temp, it.name)) }
            val file = File(temp, "$name.yml").apply { writeBytes(bytes) }
            val mine = { line: String -> line.startsWith("$name:") || line.contains("$name.yml") || line.contains("item $name:") }
            return when (kind) {
                UploadKind.IMPACT -> ImpactScenes.parse(file).second
                UploadKind.SKYBOX -> SkyboxDefinitions.load(temp).errors.filter(mine)
                UploadKind.GUI -> GuiLoader.load(temp).errors.filter(mine)
                else -> ItemDefinitions.load(temp, io.github.ratr2.repack.model.ModelLibrary.ids(models), setOf(name), materials).errors
            }
        } finally {
            temp.deleteRecursively()
        }
    }

    /**
     * A copy, not a link: the loaders check that a picture is in the folder by its real path, and a link to a file elsewhere would look as if it were not (so every
     * item with a picture next to it was refused). A file over [COPY_LIMIT] is left out: what could name it is checked without it.
     */
    private fun copy(from: File, to: File) {
        if (from.length() <= COPY_LIMIT) Files.copy(from.toPath(), to.toPath())
    }

    private const val COPY_LIMIT = 64L * 1024 * 1024
}
