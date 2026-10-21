package io.github.ratr2.repack.pack

import org.bukkit.configuration.ConfigurationSection
import java.util.Locale

/**
 * Animated text, the r4tshader pack built into Repack. Text in a color that has an effect (chat, item names, signs, anything
 * drawn as text) is drawn by the pack's text shader as a moving gradient: a shine, pulse, wave, strobe or rainbow. Effects come
 * from `text-effects` in config.yml and are written into the vertex shader of every era that can take them (1.21.6 and newer,
 * which have game time in the shader). `<fx:pink>` in a name or lore is the color of that effect's trigger.
 */
object TextEffects {

    class Rule(
        val name: String,
        val trigger: Int,
        val from: Int,
        val to: Int,
        val type: String,
        val legacyShadow: Boolean,
        val speed: Double,
        val frequency: Double,
        val bias: Double,
        val hotExponent: Double,
        val hotStrength: Double,
        val offset: Double,
    ) {
        val triggerHex: String get() = "%06x".format(trigger)
    }

    class Snippet(val definitions: String, val loop: String)

    private val TYPES = mapOf("sweep" to 0, "shine" to 0, "pulse" to 1, "wave" to 2, "strobe" to 3, "rainbow" to 4)
    private val NAME = Regex("[a-z0-9_-]+")
    private val HEX = Regex("#?([0-9a-fA-F]{6})")

    private val SUPPORTED = setOf("1.21.8", "1.21.10", "1.21.11", "26.1", "26.2", "26.3")

    fun supports(era: PackEra) = era.id in SUPPORTED

    fun rules(section: ConfigurationSection?): Pair<List<Rule>, List<String>> {
        if (section == null || !section.getBoolean("enabled", true)) return emptyList<Rule>() to emptyList()
        val errors = ArrayList<String>()
        val defaults = section.getConfigurationSection("defaults")
        fun number(from: ConfigurationSection?, key: String, fallback: Double): Double {
            val value = from?.get(key)
            return when (value) {
                null -> fallback
                is Number -> value.toDouble()
                else -> value.toString().toDoubleOrNull() ?: fallback
            }.takeIf { it.isFinite() } ?: fallback
        }
        val rules = ArrayList<Rule>()
        val effects = section.getConfigurationSection("effects")
        for (name in effects?.getKeys(false).orEmpty()) {
            val entry = effects!!.getConfigurationSection(name)
            val where = "text-effects.effects.$name"
            if (entry == null || !NAME.matches(name)) {
                errors += "$where: names use only a-z, 0-9, _ and -, and each needs its own settings."
                continue
            }
            fun color(key: String, fallback: Int?): Int? {
                val text = entry.getString(key) ?: return fallback
                val match = HEX.matchEntire(text.trim())
                if (match == null) errors += "$where.$key '$text' is not a color like #ff0cff."
                return match?.groupValues?.get(1)?.toInt(16)
            }
            val trigger = color("trigger", null)
            if (trigger == null) {
                if (!entry.contains("trigger")) errors += "$where: needs a trigger color, the text color that starts the effect."
                continue
            }
            val type = (entry.getString("type") ?: "sweep").lowercase()
            if (type !in TYPES) {
                errors += "$where.type '$type' is not one of ${TYPES.keys.joinToString()}."
                continue
            }
            val shadow = (entry.getString("shadow") ?: "match").lowercase()
            if (shadow != "match" && shadow != "legacy") {
                errors += "$where.shadow '$shadow' is not match or legacy."
                continue
            }
            val from = color("from", trigger) ?: continue
            val to = color("to", trigger) ?: continue
            if (rules.any { it.trigger == trigger }) {
                errors += "$where: another effect already uses the trigger color #%06x.".format(trigger)
                continue
            }
            rules += Rule(
                name = name, trigger = trigger, from = from, to = to, type = type, legacyShadow = shadow == "legacy",
                speed = number(entry, "speed", number(defaults, "speed", 300.0)),
                frequency = number(entry, "frequency", number(defaults, "frequency", 0.10)),
                bias = number(entry, "bias", number(defaults, "bias", 0.42)),
                hotExponent = number(entry, "hot-exponent", number(defaults, "hot-exponent", 5.0)),
                hotStrength = number(entry, "hot-strength", number(defaults, "hot-strength", 0.12)),
                offset = number(entry, "offset", 0.0),
            )
        }
        return rules to errors
    }

    private fun resource(name: String) = TextEffects::class.java.getResourceAsStream("/shader/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    fun snippet(rules: List<Rule>): Snippet? {
        if (rules.isEmpty()) return null
        val n = rules.size
        fun floats(name: String, value: (Rule) -> Double) =
            "const float $name[$n] = float[](\n" + rules.joinToString(",\n") { "    " + "%.4f".format(Locale.ROOT, value(it)) } + "\n);\n"
        fun colors(name: String, value: (Rule) -> Int) =
            "const vec3 $name[$n] = vec3[](\n" + rules.joinToString(",\n") { "    HEX(0x%06X)".format(value(it)) } + "\n);\n"
        fun ints(name: String, value: (Rule) -> Int) =
            "const int $name[$n] = int[](\n" + rules.joinToString(",\n") { "    ${value(it)}" } + "\n);\n"
        val table = buildString {
            append("// The effects of text-effects in config.yml.\n#define EFFECT_COUNT $n\n\n")
            append(colors("TRIGGER") { it.trigger }).append('\n')
            append(colors("G_FROM") { it.from }).append('\n')
            append(colors("G_TO") { it.to }).append('\n')
            append(ints("EFFECT_TYPE") { TYPES.getValue(it.type) }).append('\n')
            append(ints("SHADOW_MODE") { if (it.legacyShadow) 1 else 0 }).append('\n')
            append(floats("SPEED") { it.speed }).append('\n')
            append(floats("FREQ") { it.frequency }).append('\n')
            append(floats("BIAS") { it.bias }).append('\n')
            append(floats("HOT_EXP") { it.hotExponent }).append('\n')
            append(floats("HOT_STR") { it.hotStrength }).append('\n')
            append(floats("TIME_OFFSET") { it.offset }).append('\n')
        }
        return Snippet(resource("text_effects_head.glsl") + "\n" + table + "\n" + resource("text_effects_body.glsl"), resource("text_effects_loop.glsl").trimEnd())
    }

    private val NORMAL = Regex("^([ \\t]*)vertexColor = Color \\* (.+);$", RegexOption.MULTILINE)

    fun inject(vsh: String, snippet: Snippet): String? {
        val main = vsh.indexOf("void main() {")
        val line = NORMAL.find(vsh) ?: return null
        if (main < 0 || line.range.first < main) return null
        val indent = line.groupValues[1]
        val replacement = "${indent}vec4 c = Color;\n${snippet.loop}\n${indent}vertexColor = c * ${line.groupValues[2]};"
        return vsh.substring(0, main) + snippet.definitions + "\n\n" + vsh.substring(main, line.range.first) + replacement + vsh.substring(line.range.last + 1)
    }
}
