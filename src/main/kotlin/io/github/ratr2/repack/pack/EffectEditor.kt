package io.github.ratr2.repack.pack

import org.bukkit.configuration.file.FileConfiguration
import kotlin.math.floor
import kotlin.math.round

/**
 * Edits `text-effects` in the config for the effect screens. It only reads and writes the config; saving it and rebuilding the pack
 * are up to the caller. An effect's numbers fall back to `text-effects.defaults`, then to the built-in value, like [TextEffects.rules].
 */
object EffectEditor {

    class Field(val key: String, val label: String, val note: String, val default: Double, val step: Double, val big: Double, val min: Double, val max: Double)

    val FIELDS = listOf(
        Field("speed", "Speed", "Cycles per Minecraft day", 300.0, 25.0, 100.0, 0.0, 5000.0),
        Field("frequency", "Frequency", "How tightly the wave repeats along the text", 0.10, 0.01, 0.1, 0.0, 2.0),
        Field("bias", "Bias", "Higher is brighter, lower is darker", 0.42, 0.02, 0.1, -1.0, 1.0),
        Field("hot-exponent", "Peak sharpness", "Sharpness of the bright peak", 5.0, 0.5, 2.0, 0.5, 20.0),
        Field("hot-strength", "Peak strength", "Strength of the bright peak", 0.12, 0.01, 0.05, 0.0, 1.0),
        Field("offset", "Head start", "In cycles, so effects do not move together", 0.0, 0.05, 0.25, 0.0, 10.0),
    )

    val TYPES = listOf("shine", "pulse", "wave", "strobe", "rainbow")
    val SHADOWS = listOf("match", "legacy")

    const val MAX_EFFECTS = 21
    private const val ROOT = "text-effects"
    private val NAME = Regex("[a-z0-9_-]{1,24}")
    private val HEX = Regex("#?([0-9a-fA-F]{6})")

    fun names(config: FileConfiguration): List<String> = config.getConfigurationSection("$ROOT.effects")?.getKeys(false)?.toList().orEmpty()

    fun enabled(config: FileConfiguration) = config.getBoolean("$ROOT.enabled", true)

    fun setEnabled(config: FileConfiguration, on: Boolean) = config.set("$ROOT.enabled", on)

    private fun path(name: String?, key: String) = if (name == null) "$ROOT.defaults.$key" else "$ROOT.effects.$name.$key"

    fun number(config: FileConfiguration, name: String?, field: Field): Double {
        fun read(at: String): Double? = when (val v = config.get(at)) {
            null -> null
            is Number -> v.toDouble()
            else -> v.toString().toDoubleOrNull()
        }?.takeIf { it.isFinite() }
        return (name?.let { read(path(it, field.key)) }) ?: read(path(null, field.key)) ?: field.default
    }

    /** Whether the effect sets this number itself, rather than taking the default. */
    fun overrides(config: FileConfiguration, name: String, field: Field) = config.contains(path(name, field.key))

    fun setNumber(config: FileConfiguration, name: String?, field: Field, value: Double) {
        config.set(path(name, field.key), round(value.coerceIn(field.min, field.max) * 10000) / 10000)
    }

    fun clearNumber(config: FileConfiguration, name: String, field: Field) = config.set(path(name, field.key), null)

    fun format(value: Double): String = if (value == floor(value)) "%.1f".format(java.util.Locale.ROOT, value) else "%.4f".format(java.util.Locale.ROOT, value).trimEnd('0')

    fun color(config: FileConfiguration, name: String, key: String): Int? =
        HEX.matchEntire(config.getString(path(name, key)).orEmpty().trim())?.groupValues?.get(1)?.toInt(16)

    fun hex(color: Int) = "#%06x".format(color)

    fun type(config: FileConfiguration, name: String): String = (config.getString(path(name, "type")) ?: "shine").lowercase().let { if (it == "sweep") "shine" else it }

    fun shadow(config: FileConfiguration, name: String): String = (config.getString(path(name, "shadow")) ?: "match").lowercase()

    fun cycleType(config: FileConfiguration, name: String, backwards: Boolean = false) = config.set(path(name, "type"), next(TYPES, type(config, name), backwards))

    fun cycleShadow(config: FileConfiguration, name: String) = config.set(path(name, "shadow"), next(SHADOWS, shadow(config, name), false))

    private fun next(list: List<String>, current: String, backwards: Boolean): String {
        val at = list.indexOf(current).coerceAtLeast(0)
        return list[(at + if (backwards) list.size - 1 else 1) % list.size]
    }

    /** Sets a color from text like `#ff0cff`. Returns what is wrong with it, or null. A trigger must also be free: two effects cannot start from one color. */
    fun setColor(config: FileConfiguration, name: String, key: String, text: String): String? {
        val match = HEX.matchEntire(text.trim()) ?: return "'$text' is not a color like #ff0cff."
        val color = match.groupValues[1].toInt(16)
        if (key == "trigger") {
            val other = names(config).firstOrNull { it != name && color(config, it, "trigger") == color }
            if (other != null) return "${hex(color)} already starts the effect $other."
        }
        config.set(path(name, key), hex(color))
        return null
    }

    /** Makes a new effect with a free trigger color and a gradient around it. Returns what is wrong with the name, or null. */
    fun create(config: FileConfiguration, name: String): String? {
        if (!NAME.matches(name)) return "Names use a-z, 0-9, _ and -, up to 24 characters."
        if (name in names(config)) return "There is already an effect called $name."
        if (names(config).size >= MAX_EFFECTS) return "The editor shows $MAX_EFFECTS effects; edit config.yml for more."
        val taken = names(config).mapNotNull { color(config, it, "trigger") }.toSet()
        var hue = (name.hashCode() and 0x7fffffff) % 360
        var trigger: Int
        do {
            trigger = java.awt.Color.HSBtoRGB(hue / 360f, 0.75f, 0.9f) and 0xFFFFFF
            hue = (hue + 7) % 360
        } while (trigger in taken)
        val hue0 = (hue + 353) % 360
        config.set(path(name, "trigger"), hex(trigger))
        config.set(path(name, "from"), hex(scale(trigger, 0.55)))
        config.set(path(name, "to"), hex(java.awt.Color.HSBtoRGB(hue0 / 360f, 0.45f, 1f) and 0xFFFFFF))
        config.set(path(name, "type"), "shine")
        return null
    }

    private fun scale(color: Int, factor: Double): Int {
        fun channel(shift: Int) = (((color shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun delete(config: FileConfiguration, name: String) = config.set("$ROOT.effects.$name", null)

    /** The effects as `<fx:name>` triggers, for chat and item names. */
    fun refresh(config: FileConfiguration) {
        io.github.ratr2.repack.text.Colors.effects = TextEffects.rules(config.getConfigurationSection(ROOT)).first.associate { it.name to it.triggerHex }
    }
}
