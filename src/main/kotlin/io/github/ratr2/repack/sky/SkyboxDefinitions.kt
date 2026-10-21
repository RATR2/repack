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
    private const val MAX_PICTURE_SIDE = 16384
    private const val MAX_SHADER_BYTES = 64 * 1024

    private val FORBIDDEN = listOf("#version", "#extension", "#include", "#moj_import", "void main", "gl_Position", "gl_FragDepth")

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
        FORBIDDEN.firstOrNull { it in code }?.let { throw SkyboxException("the shader may not contain '$it': it is only the statements that work out the color, see _example.yml.") }
        return code.replace("\r\n", "\n")
    }

    fun best(skies: List<SkyboxDefinition>, dimension: String, world: String, biome: String, has: (String) -> Boolean): SkyboxDefinition? =
        skies.filter { it.matches(dimension, world, biome) && (it.permission == null || has(it.permission)) }
            .maxWithOrNull(compareBy({ it.priority }, { it.specificity }, { -it.code }))

    val BUNDLED = listOf("gradient", "stars", "clouds")

    fun bundled(name: String): String? =
        if (name in BUNDLED) SkyboxDefinitions::class.java.getResourceAsStream("/skybox/builtin/$name.glsl")?.use { String(it.readBytes(), Charsets.UTF_8) } else null
}
