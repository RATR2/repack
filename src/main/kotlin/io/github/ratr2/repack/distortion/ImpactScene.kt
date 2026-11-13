package io.github.ratr2.repack.distortion

import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

/**
 * A set of focus points to show together: build it first (from code, Skript, or a file in `impacts/`), then play it for players in one go. A focus point is
 * either at a place in the world, or relative to an origin: so many blocks to the right, up and ahead of it (the origin is the player's eyes when the scene is
 * played without one), which is what lets a scene in a file be played anywhere.
 */
class ImpactScene(val name: String) {

    class Entry(
        val type: ImpactManager.Type, val size: Double, val location: Location?,
        val right: Double = 0.0, val up: Double = 0.0, val forward: Double = DEFAULT_FORWARD,
        val direction: Vector? = null, val screenAngle: Double? = null,
    )

    val entries = ArrayList<Entry>()

    /** How long it holds when played without a length (1 to 200), and the light tone (the dark tone is black). */
    var ticks = 4

    var rgb = 0xFFFFFF

    /** Adds a focus point at a place in the world. False when the type is unknown. */
    @JvmOverloads
    fun add(location: Location, type: String, size: Double, direction: Vector? = null, screenAngle: Double? = null): Boolean {
        val kind = ImpactManager.Type.of(type) ?: return false
        entries += Entry(kind, size, location.clone(), direction = direction, screenAngle = screenAngle)
        return true
    }

    /** Adds a focus point that many blocks right of, above and ahead of the origin. False when the type is unknown. */
    @JvmOverloads
    fun addRelative(right: Double, up: Double, forward: Double, type: String, size: Double, screenAngle: Double? = null): Boolean {
        val kind = ImpactManager.Type.of(type) ?: return false
        entries += Entry(kind, size, null, right, up, forward, screenAngle = screenAngle)
        return true
    }

    fun clear() = entries.clear()

    val size: Int get() = entries.size

    /** The focus points for a player: relative ones are placed from [origin], which is the player's eyes when null. */
    fun resolve(player: Player, origin: Location? = null): List<ImpactManager.Focus> {
        val base = origin ?: player.eyeLocation
        val yaw = Math.toRadians(base.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw))
        val right = Vector(-cos(yaw), 0.0, -sin(yaw))
        return entries.map { entry ->
            val at = entry.location ?: base.clone().add(right.clone().multiply(entry.right)).add(0.0, entry.up, 0.0).add(forward.clone().multiply(entry.forward))
            ImpactManager.Focus(at, entry.type, entry.size, entry.direction, entry.screenAngle)
        }
    }

    /** Shows the scene to a player, with the length and color it was given or these ones. False when the client cannot show it or the scene is empty. */
    @JvmOverloads
    fun play(player: Player, ticks: Int = this.ticks, rgb: Int = this.rgb, origin: Location? = null): Boolean =
        entries.isNotEmpty() && DistortionAPI.playFoci(player, ticks, rgb, resolve(player, origin))

    companion object {
        /** Ahead of the origin when a file says nothing: far enough to be in front of the camera. */
        const val DEFAULT_FORWARD = 8.0

        /** The most a scene file's offset or angle can be: past it a value is a mistake, and an infinite or NaN one would reach the client's glyph scale. */
        const val MAX_OFFSET = 1.0e6
    }
}

/** The scenes by name: the ones in files in `impacts/` (read again by [load]) and the ones built at run time. */
object ImpactScenes {

    private val scenes = ConcurrentHashMap<String, ImpactScene>()
    private val fromFiles = HashSet<String>()

    val ids: List<String> get() = scenes.keys.sorted()

    fun get(name: String): ImpactScene? = scenes[name.lowercase()]

    /** The scene by that name, a new empty one when there is none. */
    fun getOrCreate(name: String): ImpactScene = scenes.getOrPut(name.lowercase()) { ImpactScene(name.lowercase()) }

    fun remove(name: String): Boolean = scenes.remove(name.lowercase()) != null

    /** Reads one scene file without adding it to the scenes: the scene, and what is wrong with it. */
    fun parse(file: File): Pair<ImpactScene, List<String>> {
        val errors = ArrayList<String>()
        val scene = ImpactScene(file.nameWithoutExtension.lowercase())
        val yaml = try {
            YamlConfiguration().apply { load(file) }
        } catch (e: org.bukkit.configuration.InvalidConfigurationException) {
            return scene to listOf("impacts/${file.name}: ${e.message?.lineSequence()?.firstOrNull()}")
        } catch (e: java.io.IOException) {
            return scene to listOf("impacts/${file.name}: ${e.message}")
        }
        scene.ticks = yaml.getInt("ticks", 4).coerceIn(1, ImpactManager.MAX_TICKS)
        // The color is read as the text it is: YAML takes 001122 for an octal number and 000000 for 0, so a color that is not in quotes is told to be.
        val given = yaml.get("color")
        if (given != null) {
            // Digits that are not in quotes come out of YAML as a number (001122 as an octal one), so the six digits are taken from the line itself.
            val written = Regex("(?m)^color:\\s*[\"']?#?([0-9a-fA-F]{6})[\"']?\\s*(?:#.*)?$").find(file.readText())?.groupValues?.get(1)
            val parsed = (written ?: (given as? String)?.removePrefix("#")?.trim()?.takeIf { it.length == 6 })?.toIntOrNull(16)
            if (parsed == null) errors += "impacts/${file.name}: color must be 6 hex digits, like ff2030, not '$given'."
            else scene.rgb = parsed
        }
        for ((index, raw) in yaml.getMapList("focus").withIndex()) {
            val where = "impacts/${file.name} focus ${index + 1}"
            val type = ImpactManager.Type.of(raw["type"]?.toString())
            if (type == null) {
                errors += "$where: the type is one of ${ImpactManager.Type.entries.joinToString { it.id }}."
                continue
            }
            fun number(key: String, fallback: Double) = (raw[key] as? Number)?.toDouble() ?: raw[key]?.toString()?.toDoubleOrNull() ?: fallback
            val bad = listOf("right", "up", "forward", "angle").firstOrNull { raw.containsKey(it) && number(it, 0.0).let { v -> !v.isFinite() || Math.abs(v) > ImpactScene.MAX_OFFSET } }
            if (bad != null) {
                errors += "$where: $bad must be a number between -${ImpactScene.MAX_OFFSET.toInt()} and ${ImpactScene.MAX_OFFSET.toInt()}."
                continue
            }
            val size = number("size", 4.0)
            if (!size.isFinite() || size < ImpactManager.MIN_SIZE || size > ImpactManager.MAX_SIZE) {
                errors += "$where: size is ${ImpactManager.MIN_SIZE} to ${ImpactManager.MAX_SIZE} blocks."
                continue
            }
            scene.entries += ImpactScene.Entry(
                type, size, null, number("right", 0.0), number("up", 0.0), number("forward", ImpactScene.DEFAULT_FORWARD),
                screenAngle = if (raw.containsKey("angle")) number("angle", 0.0) else null,
            )
        }
        if (scene.entries.isEmpty()) errors += "impacts/${file.name}: no usable focus point."
        return scene to errors
    }

    /** Reads `<name>.yml` of a folder, replacing the scenes that came from files before. Returns what is wrong with a file; the rest still load. */
    @Synchronized
    fun load(folder: File): List<String> {
        fromFiles.forEach(scenes::remove)
        fromFiles.clear()
        val errors = ArrayList<String>()
        for (file in folder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }) {
            val (scene, problems) = parse(file)
            errors += problems
            scenes[scene.name] = scene
            fromFiles += scene.name
        }
        return errors
    }
}
