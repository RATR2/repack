package io.github.ratr2.repack.sky

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

class LoadedSkyboxes(val definitions: List<SkyboxDefinition>, val errors: List<String>)

object SkyboxDefinitions {

    /** The sky code is the alpha of the sprite, 1 to 24: the game never draws a pixel that transparent (it discards under 25 of 255), so no ordinary item can look like one. */
    const val MAX_SKYBOXES = 24

    private val ID = Regex("[a-z0-9_-]+")
    // 8192 by 4096 is 128 MB decoded: a picture past it is more than the build can hold, and more than the client's block atlas can take.
    private const val MAX_PICTURE_SIDE = 8192
    private const val MAX_PICTURE_PIXELS = 8192L * 4096
    private const val MAX_SHADER_BYTES = 64 * 1024

    private val FORBIDDEN = listOf("gl_Position", "gl_FragDepth")
    private val DIRECTIVE = Regex("#\\s*(version|extension|include|moj_import)")
    private val MAIN = Regex("\\bvoid\\s+main\\b")

    private val BUILTIN = Regex("builtin:([a-z0-9_-]+)")

    fun load(folder: File, builtin: (String) -> String? = ::bundled): LoadedSkyboxes {
        val errors = ArrayList<String>()
        val parsed = ArrayList<Parsed>()
        val files = folder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val id = file.nameWithoutExtension.lowercase()
            if (!ID.matches(id)) {
                errors += "${file.name}: skybox file names may only use a-z, 0-9, _ and -."
                continue
            }
            try {
                val yaml = YamlConfiguration().apply { load(file) }
                parsed += parse(id, yaml, folder, builtin)
            } catch (e: SkyboxException) {
                errors += "$id: ${e.message}"
            } catch (e: org.bukkit.configuration.InvalidConfigurationException) {
                errors += "${file.name}: ${e.message?.lineSequence()?.firstOrNull()}"
            } catch (e: java.io.IOException) {
                errors += "${file.name}: ${e.message}"
            }
        }
        val definitions = parsed.sortedBy { it.id }.take(MAX_SKYBOXES).mapIndexed { index, p ->
            SkyboxDefinition(p.id, p.source, p.priority, p.fog, p.dimensions, p.worlds, p.biomes, p.permission, index + 1)
        }
        if (parsed.size > MAX_SKYBOXES) errors += "There can be at most $MAX_SKYBOXES skyboxes; ${parsed.drop(MAX_SKYBOXES).joinToString { it.id }} were left out."
        return LoadedSkyboxes(definitions, errors)
    }

    private class Parsed(
        val id: String, val source: SkySource, val priority: Int, val fog: Double,
        val dimensions: Set<String>, val worlds: Set<String>, val biomes: Set<String>, val permission: String?,
    )

    private class SkyboxException(message: String) : Exception(message)

    private fun parse(id: String, yaml: ConfigurationSection, folder: File, builtin: (String) -> String?): Parsed {
        val named = yaml.getString("source")?.trim()?.takeIf { it.isNotEmpty() }
        val source = when {
            named == null -> listOf("$id.png", "$id.glsl").map { File(folder, it) }.firstOrNull { it.isFile }?.let { sourceOf(it) }
                ?: throw SkyboxException("there is no $id.png or $id.glsl next to it; name the file in `source`.")
            BUILTIN.matches(named) -> {
                val name = BUILTIN.matchEntire(named)!!.groupValues[1]
                SkySource.Shader(checkShader(builtin(name) ?: throw SkyboxException("there is no bundled sky called '$name'. They are: ${BUNDLED.joinToString()}.")))
            }
            else -> {
                val file = File(folder, named)
                if (file.canonicalFile.parentFile != folder.canonicalFile) throw SkyboxException("source must be a file in the skyboxes folder.")
                if (!file.isFile) throw SkyboxException("source '$named' is not a file in the skyboxes folder.")
                sourceOf(file)
            }
        }
        val priority = yaml.getInt("priority", 100)
        val fog = yaml.getDouble("fog", 0.25)
        if (!fog.isFinite() || fog < 0.0 || fog > 1.0) throw SkyboxException("fog must be between 0 and 1.")
        val `when` = yaml.getConfigurationSection("when")
        fun names(key: String) = `when`?.getStringList(key).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        return Parsed(
            id, source, priority, fog, names("dimensions").mapTo(HashSet()) { if (':' in it) it else "minecraft:$it" }, names("worlds"),
            names("biomes").mapTo(HashSet()) { if (':' in it) it else "minecraft:$it" }, yaml.getString("permission")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun sourceOf(file: File): SkySource = when (file.extension.lowercase()) {
        "png" -> {
            val bytes = file.readBytes()
            // The size is read from the header first, so a huge picture is refused before it is decoded.
            val size = imageSize(bytes) ?: throw SkyboxException("${file.name} is not an image.")
            pictureProblem(size.first, size.second)?.let { throw SkyboxException("${file.name}: $it") }
            val image = try {
                ImageIO.read(ByteArrayInputStream(bytes))
            } catch (e: LinkageError) {
                throw SkyboxException("this server's Java has no image support (the java.desktop module), which texture skies need.")
            } ?: throw SkyboxException("${file.name} is not an image.")
            if (image.width < 8 || image.height < 4 || image.width > MAX_PICTURE_SIDE || image.height > MAX_PICTURE_SIDE) {
                throw SkyboxException("${file.name} is ${image.width} by ${image.height}: it must be at least 8 by 4 and at most $MAX_PICTURE_SIDE on each side.")
            }
            SkySource.Texture(bytes, image.width, image.height)
        }
        "glsl" -> SkySource.Shader(checkShader(file.readText()))
        else -> throw SkyboxException("${file.name} must be a .png or a .glsl file.")
    }

    private fun checkShader(code: String): String {
        if (code.isBlank()) throw SkyboxException("the shader is empty.")
        if (code.toByteArray().size > MAX_SHADER_BYTES) throw SkyboxException("the shader is longer than ${MAX_SHADER_BYTES / 1024} KB.")
        // Comments are taken out first: what is left is what the game compiles, and a comment may say anything.
        val stripped = withoutComments(code) ?: throw SkyboxException("the shader has a /* comment that is never closed.")
        DIRECTIVE.find(stripped)?.let { throw SkyboxException("the shader may not contain '${it.value}': it is only the statements that work out the color, see _example.yml.") }
        if (MAIN.containsMatchIn(stripped)) throw SkyboxException("the shader may not contain 'void main': it is only the statements that work out the color, see _example.yml.")
        FORBIDDEN.firstOrNull { it in stripped }?.let { throw SkyboxException("the shader may not contain '$it': it is only the statements that work out the color, see _example.yml.") }
        // The code is pasted into a function: a brace that closes more than it opens, or one left open, would break the shader of every client.
        var depth = 0
        for (c in stripped) {
            if (c == '{') depth++ else if (c == '}') depth--
            if (depth < 0) throw SkyboxException("the shader has a } with no { before it.")
        }
        if (depth != 0) throw SkyboxException("the shader has a { that is never closed.")
        return code.replace("\r\n", "\n")
    }

    /** The code without its comments, or null when a block comment is opened and never closed. */
    private fun withoutComments(code: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < code.length) {
            if (code.startsWith("//", i)) {
                while (i < code.length && code[i] != '\n') i++
            } else if (code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2)
                if (end < 0) return null
                out.append(' ')
                i = end + 2
            } else {
                out.append(code[i++])
            }
        }
        return out.toString()
    }

    /** The picture's width and height from its header, without decoding it; null when it is not an image. */
    private fun imageSize(bytes: ByteArray): Pair<Int, Int>? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return@use null
            try {
                reader.setInput(input)
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: LinkageError) {
        throw SkyboxException("this server's Java has no image support (the java.desktop module), which texture skies need.")
    } catch (e: Exception) {
        null
    }

    /** What is wrong with a shader file's text, or null: the same checks the loader makes. */
    fun shaderProblem(code: String): String? = try {
        checkShader(code)
        null
    } catch (e: SkyboxException) {
        e.message
    }

    /** What is wrong with a skybox picture's size, or null. */
    fun pictureProblem(width: Int, height: Int): String? =
        if (width < 8 || height < 4 || width > MAX_PICTURE_SIDE || height > MAX_PICTURE_SIDE || width.toLong() * height > MAX_PICTURE_PIXELS) "it is $width by $height: it must be at least 8 by 4 and at most $MAX_PICTURE_SIDE on each side and 8192 by 4096 in all." else null

    fun best(skies: List<SkyboxDefinition>, dimension: String, world: String, biome: String, has: (String) -> Boolean): SkyboxDefinition? =
        skies.filter { it.matches(dimension, world, biome) && (it.permission == null || has(it.permission)) }
            .maxWithOrNull(compareBy({ it.priority }, { it.specificity }, { -it.code }))

    val BUNDLED = listOf("gradient", "stars", "clouds")

    fun bundled(name: String): String? =
        if (name in BUNDLED) SkyboxDefinitions::class.java.getResourceAsStream("/skybox/builtin/$name.glsl")?.use { String(it.readBytes(), Charsets.UTF_8) } else null
}
