package io.github.ratr2.repack.menu

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Turns the pictures GUI files name into screens, the way Repack's own screens work: a bitmap font glyph used as the chest's title,
 * pulled left over the screen's edge. Nothing needs to be drawn by hand; the font, the glyph and the sizing are made at build.
 */
object GuiBackgrounds {

    val FONT: Key = Key.key("repack", "guis")

    private const val PAD = 8
    private const val BELOW = 4
    private const val ASCENT = 13 + PAD
    private const val MAX_SCALE = 8

    /** Moves the cursor 16 pixels left, from where a title starts, to the edge of the picture. */
    private const val SHIFT = ''
    private const val FIRST_GLYPH = 0xE001

    data class ShapeKey(val image: String, val kind: GuiKind, val rows: Int, val holes: HoleMode)

    class Slot(val x: Int, val y: Int)

    class Geometry(val height: Int, val container: List<Slot>, val player: List<Slot>)

    fun keyOf(def: GuiDef): ShapeKey? = def.background?.takeIf { def.kind.hasSlots && !def.template }?.let { ShapeKey(it.image, def.kind, if (def.kind == GuiKind.CHEST) def.rows else 0, it.holes) }

    /** Glyphs go by the pictures' names, so a build and the server running it agree without talking to each other. */
    fun glyphs(definitions: Collection<GuiDef>): Map<ShapeKey, Char> =
        definitions.mapNotNull(::keyOf).toSortedSet(compareBy({ it.image }, { it.kind }, { it.rows }, { it.holes }))
            .withIndex().associate { (i, key) -> key to (FIRST_GLYPH + i).toChar() }

    fun geometry(kind: GuiKind, rows: Int): Geometry? {
        fun grid(x: Int, y: Int, columns: Int, count: Int) = List(count) { Slot(x + it % columns * 18, y + it / columns * 18) }
        return when (kind) {
            GuiKind.CHEST -> Geometry(18 * rows + 114, grid(8, 18, 9, rows * 9), grid(8, 18 * rows + 31, 9, 27) + grid(8, 18 * rows + 89, 9, 9))
            GuiKind.HOPPER -> Geometry(133, grid(44, 20, 5, 5), grid(8, 51, 9, 27) + grid(8, 109, 9, 9))
            GuiKind.DISPENSER, GuiKind.DROPPER -> Geometry(166, grid(62, 17, 3, 9), grid(8, 84, 9, 27) + grid(8, 142, 9, 9))
            else -> null
        }
    }

    fun title(glyph: Char): Component = Component.text("$SHIFT$glyph").font(FONT).color(NamedTextColor.WHITE)

    fun files(definitions: Collection<GuiDef>, folder: File, errors: MutableList<String> = ArrayList()): Map<String, ByteArray> {
        val glyphs = glyphs(definitions)
        if (glyphs.isEmpty()) return emptyMap()
        val files = HashMap<String, ByteArray>()
        val providers = JsonArray()
        providers.add(JsonObject().apply {
            addProperty("type", "space")
            add("advances", JsonObject().apply { addProperty(SHIFT.toString(), -(8 + PAD)) })
        })
        for ((key, glyph) in glyphs) {
            val geometry = geometry(key.kind, key.rows) ?: continue
            val source = try {
                ImageIO.read(File(folder, key.image))
            } catch (e: java.io.IOException) {
                null
            }
            if (source == null) {
                errors += "${key.image}: not a picture Repack can read."
                continue
            }
            val height = geometry.height + PAD + BELOW
            val picture = fitted(source, 2 * PAD + 176, height, geometry, key.holes)
            val index = glyph.code - FIRST_GLYPH
            files["assets/repack/textures/gui/menus/m$index.png"] = png(picture.image)
            providers.add(JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", "repack:gui/menus/m$index.png")
                addProperty("ascent", ASCENT)
                addProperty("height", height)
                add("chars", JsonArray().apply { add(glyph.toString()) })
            })
        }
        files["assets/repack/font/guis.json"] = JsonObject().apply { add("providers", providers) }.toString().toByteArray(Charsets.UTF_8)
        return files
    }

    class Fitted(val image: BufferedImage, val scale: Int)

    /**
     * The picture at the screen's proportions: the scale is how many times larger it is than the game's pixels (a 2x picture is fine),
     * a picture of other proportions is stretched to fit. Holes are cut after, so they stay sharp.
     */
    fun fitted(source: BufferedImage, width: Int, height: Int, geometry: Geometry, holes: HoleMode): Fitted {
        val scale = (source.height.toDouble() / height).roundToInt().coerceIn(1, MAX_SCALE)
        val image = if (source.width == width * scale && source.height == height * scale && source.type == BufferedImage.TYPE_INT_ARGB) {
            source
        } else {
            BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB).also { out ->
                val g = out.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.drawImage(source, 0, 0, out.width, out.height, null)
                g.dispose()
            }
        }
        if (holes != HoleMode.OFF) {
            val slots = if (holes == HoleMode.ALL) geometry.container + geometry.player else geometry.container
            for (slot in slots) punch(image, (slot.x + PAD) * scale, (slot.y + PAD) * scale, 16 * scale)
        }
        return Fitted(image, scale)
    }

    private fun punch(image: BufferedImage, x: Int, y: Int, size: Int) {
        for (py in y until minOf(y + size, image.height)) for (px in x until minOf(x + size, image.width)) image.setRGB(px, py, 0)
    }

    private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    fun template(kind: GuiKind, rows: Int): BufferedImage? {
        val geometry = geometry(kind, rows) ?: return null
        val scale = 2
        val image = BufferedImage((2 * PAD + 176) * scale, (geometry.height + PAD + BELOW) * scale, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(40, 44, 52, 255)
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color(120, 130, 150, 255)
        g.stroke = java.awt.BasicStroke(scale.toFloat())
        g.drawRect(PAD * scale, PAD * scale, 176 * scale, geometry.height * scale)
        g.font = g.font.deriveFont(6f * scale)
        geometry.container.forEachIndexed { index, slot ->
            g.color = Color(90, 200, 120, 255)
            g.drawRect((slot.x + PAD) * scale, (slot.y + PAD) * scale, 16 * scale, 16 * scale)
            g.color = Color(200, 240, 210, 255)
            g.drawString(index.toString(), (slot.x + PAD + 1) * scale, (slot.y + PAD + 8) * scale)
        }
        for (slot in geometry.player) {
            g.color = Color(110, 120, 140, 255)
            g.drawRect((slot.x + PAD) * scale, (slot.y + PAD) * scale, 16 * scale, 16 * scale)
        }
        g.dispose()
        return image
    }

    fun templatePng(kind: GuiKind, rows: Int): ByteArray? = template(kind, rows)?.let(::png)
}
