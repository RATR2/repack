package io.github.ratr2.repack.pack

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared text shader rules. Repack owns the pack's only text shader, so other plugins register declarative rules here (through
 * RepackAPI.registerTextRule) instead of shipping a shader of their own. A rule gets a marker color from a reserved range; text drawn
 * in that color is moved by the shader to its anchor plus an offset and drawn in the real color. The rules are written into the
 * vertex shader of every era at build time. GUI text only: the projection tells the shader how big the scaled GUI is, so no era needs
 * ScreenSize or GuiScale.
 */
object TextRules {

    /** Points of the screen, as a fraction of the scaled GUI width and height. */
    enum class Anchor(val x: Double, val y: Double) {
        TOP_LEFT(0.0, 0.0), TOP_CENTER(0.5, 0.0), TOP_RIGHT(1.0, 0.0),
        CENTER_LEFT(0.0, 0.5), CENTER(0.5, 0.5), CENTER_RIGHT(1.0, 0.5),
        BOTTOM_LEFT(0.0, 1.0), BOTTOM_CENTER(0.5, 1.0), BOTTOM_RIGHT(1.0, 1.0),
    }

    /**
     * [anchor] and [offsetX]/[offsetY] (GUI pixels, y down) say where the text goes. [source] and [sourceX]/[sourceY] say where vanilla
     * draws that kind of text (a boss bar title hangs from the top center), because the shader sees glyphs one by one and moves each by
     * the distance between the two points. [scale] grows or shrinks the text around its target. [color] is what the text is drawn in.
     */
    class Rule(
        val id: String,
        val marker: Int,
        val anchor: Anchor,
        val offsetX: Double,
        val offsetY: Double,
        val source: Anchor,
        val sourceX: Double,
        val sourceY: Double,
        val scale: Double,
        val color: Int,
    )

    /** Marker colors are 0xFEFD00 plus a multiple of 4 in the blue channel, so a marker's drop shadow (a quarter of it) never matches another marker. */
    const val MAX_RULES = 64
    private const val MARKER_BASE = 0xFEFD00
    private val ID = Regex("[a-z0-9_.:/-]+")

    private val rules = ConcurrentHashMap<String, Rule>()

    /** Bumped on every change, so the build and status can tell the pack no longer matches the rules. */
    @Volatile var version = 0
        private set

    fun all(): List<Rule> = rules.values.sortedBy { it.marker }

    fun get(id: String): Rule? = rules[id]

    fun markerOf(id: String): Int? = rules[id]?.marker

    /**
     * Registers or replaces a rule. Replacing keeps the marker, so text a plugin already colored stays valid. Returns the rule, or null
     * when the id is malformed, a value is not finite, or all [MAX_RULES] markers are taken.
     */
    @Synchronized
    fun register(
        id: String, anchor: Anchor, offsetX: Double, offsetY: Double,
        source: Anchor = Anchor.TOP_CENTER, sourceX: Double = 0.0, sourceY: Double = 0.0,
        scale: Double = 1.0, color: Int = 0xFFFFFF,
    ): Rule? {
        if (!ID.matches(id) || listOf(offsetX, offsetY, sourceX, sourceY, scale).any { !it.isFinite() } || scale <= 0.0) return null
        val marker = rules[id]?.marker ?: free() ?: return null
        val rule = Rule(id, marker, anchor, offsetX, offsetY, source, sourceX, sourceY, scale, color and 0xFFFFFF)
        rules[id] = rule
        version++
        return rule
    }

    @Synchronized
    fun unregister(id: String): Boolean {
        val removed = rules.remove(id) != null
        if (removed) version++
        return removed
    }

    /** Drops every rule an owner registered under `owner:`, for a plugin that is disabling. */
    @Synchronized
    fun unregisterAll(prefix: String): Int {
        val ids = rules.keys.filter { it.startsWith(prefix) }
        ids.forEach { rules.remove(it) }
        if (ids.isNotEmpty()) version++
        return ids.size
    }

    private fun free(): Int? {
        val used = rules.values.map { it.marker }.toSet()
        return (0 until MAX_RULES).map { MARKER_BASE or (it * 4) }.firstOrNull { it !in used }
    }

    /** The drop shadow the client draws for a text color: each channel divided by 4. */
    fun shadowOf(color: Int): Int = ((color shr 18) and 0x3F shl 16) or ((color shr 10) and 0x3F shl 8) or ((color shr 2) and 0x3F)

    class Snippet(val definitions: String, val call: String)

    fun snippet(list: List<Rule> = all()): Snippet? {
        if (list.isEmpty()) return null
        val n = list.size
        fun f(v: Double) = "%.4f".format(Locale.ROOT, v)
        fun table(type: String, name: String, value: (Rule) -> String) =
            "const $type $name[$n] = $type[](\n" + list.joinToString(",\n") { "    " + value(it) } + "\n);\n"
        fun rgb(c: Int) = "vec3(${c shr 16 and 0xFF}.0, ${c shr 8 and 0xFF}.0, ${c and 0xFF}.0)"
        val definitions = buildString {
            append("// Shared text rules registered through RepackAPI.registerTextRule.\n#define TEXT_RULE_COUNT $n\n\n")
            append(table("vec3", "RULE_MARKER") { rgb(it.marker) }).append('\n')
            append(table("vec3", "RULE_SHADOW") { rgb(shadowOf(it.marker)) }).append('\n')
            append(table("vec3", "RULE_COLOR") { rgb(it.color) }).append('\n')
            append(table("vec2", "RULE_TO") { "vec2(${f(it.anchor.x)}, ${f(it.anchor.y)})" }).append('\n')
            append(table("vec2", "RULE_TO_PX") { "vec2(${f(it.offsetX)}, ${f(it.offsetY)})" }).append('\n')
            append(table("vec2", "RULE_FROM") { "vec2(${f(it.source.x)}, ${f(it.source.y)})" }).append('\n')
            append(table("vec2", "RULE_FROM_PX") { "vec2(${f(it.sourceX)}, ${f(it.sourceY)})" }).append('\n')
            append(table("float", "RULE_SCALE") { f(it.scale) }).append('\n')
            append(
                """
                |bool ruleMatches(vec3 rgb, vec3 want) {
                |    return all(lessThan(abs(rgb * 255.0 - want), vec3(0.5)));
                |}
                |
                |// Moves GUI text (an orthographic projection) whose color is a marker, and swaps in the rule's color.
                |void applyTextRules(inout vec3 pos, inout vec4 c) {
                |    if (ProjMat[3][3] != 1.0) return;
                |    vec2 gui = vec2(2.0 / ProjMat[0][0], -2.0 / ProjMat[1][1]);
                |    for (int i = 0; i < TEXT_RULE_COUNT; i++) {
                |        bool shadow = ruleMatches(c.rgb, RULE_SHADOW[i]);
                |        if (!shadow && !ruleMatches(c.rgb, RULE_MARKER[i])) continue;
                |        vec2 from = RULE_FROM[i] * gui + RULE_FROM_PX[i];
                |        vec2 to = RULE_TO[i] * gui + RULE_TO_PX[i];
                |        pos.xy = to + (pos.xy - from) * RULE_SCALE[i];
                |        c.rgb = RULE_COLOR[i] / 255.0 * (shadow ? 0.25 : 1.0);
                |        break;
                |    }
                |}
                |""".trimMargin(),
            )
        }
        return Snippet(definitions, "    vec3 textPos = Position;\n    vec4 textColor = Color;\n    applyTextRules(textPos, textColor);")
    }

    private val POSITION = Regex("^([ \\t]*)gl_Position = ProjMat \\* ModelViewMat \\* vec4\\(Position, 1\\.0\\);$", RegexOption.MULTILINE)
    private val COLOR = Regex("vertexColor = Color\\b")

    /** Null when the shader is not one Repack knows how to extend. */
    fun inject(vsh: String, snippet: Snippet): String? {
        val main = vsh.indexOf("void main() {")
        val line = POSITION.find(vsh) ?: return null
        if (main < 0 || line.range.first < main) return null
        val tail = vsh.substring(line.range.last + 1)
        if (!COLOR.containsMatchIn(tail)) return null
        val indent = line.groupValues[1]
        val replacement = snippet.call + "\n${indent}gl_Position = ProjMat * ModelViewMat * vec4(textPos, 1.0);"
        return vsh.substring(0, main) + snippet.definitions + "\n" + vsh.substring(main, line.range.first) + replacement +
            tail.replace(COLOR, "vertexColor = textColor")
    }
}
