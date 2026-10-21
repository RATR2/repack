package io.github.ratr2.repack.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.floor

/** A triangle or quad mesh read from a Wavefront .obj file: the corners of each face are (position index, uv index) pairs. */
class ObjMesh(val positions: List<DoubleArray>, val uvs: List<DoubleArray>, val faces: List<List<IntArray>>) {

    companion object {
        /** Reads `v`, `vt` and `f` lines; every other line is ignored. A face needs three or four corners, and extra ones are dropped. */
        fun parse(text: String): ObjMesh {
            val positions = ArrayList<DoubleArray>()
            val uvs = ArrayList<DoubleArray>()
            val faces = ArrayList<List<IntArray>>()
            for (raw in text.lineSequence()) {
                val parts = raw.trim().split(Regex("\\s+"))
                when (parts[0]) {
                    "v" -> positions += DoubleArray(3) { parts.getOrNull(it + 1)?.toDoubleOrNull() ?: throw BbParseException("bad vertex line '$raw'.") }
                    "vt" -> uvs += DoubleArray(2) { parts.getOrNull(it + 1)?.toDoubleOrNull() ?: throw BbParseException("bad uv line '$raw'.") }
                    "f" -> faces += parts.drop(1).map { corner ->
                        val numbers = corner.split("/")
                        val position = numbers[0].toIntOrNull() ?: throw BbParseException("bad face line '$raw'.")
                        val uv = numbers.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: throw BbParseException("the face '$raw' has no texture coordinates.")
                        intArrayOf(position - 1, uv - 1)
                    }
                }
            }
            if (faces.isEmpty()) throw BbParseException("the model has no faces.")
            for (face in faces) {
                if (face.size < 3) throw BbParseException("a face has fewer than three corners.")
                for (corner in face) {
                    if (corner[0] !in positions.indices || corner[1] !in uvs.indices) throw BbParseException("a face points at a vertex or uv that does not exist.")
                }
            }
            return ObjMesh(positions, uvs, faces)
        }
    }
}

/**
 * The model format of objmc (https://github.com/Godlander/objmc, MIT): a mesh is stored in the pixels of a texture, and a core shader
 * builds it again in the game, so models that are not made of cubes can be shown. This turns one mesh and its texture into that texture
 * and a model file made of one flat element per face, exactly as objmc.py does for a single, static model.
 */
object Objmc {

    class Encoded(val png: ByteArray, val modelJson: String, val faces: Int, val warnings: List<String>)

    private const val CENTER = 8388608.0

    /**
     * [texturePng] is the model's texture, at least 8 pixels wide. [textureName] is the texture path the model file points at, like
     * `repack:block/chair/data`. [scale] and [offset] move the vertices before they are stored. With [flipUv] the texture is stored
     * as it is, otherwise upside down, which is what a .obj from Blender expects.
     */
    fun encode(mesh: ObjMesh, texturePng: ByteArray, textureName: String, scale: Double = 1.0, offset: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0), flipUv: Boolean = false, noShadow: Boolean = false, autoRotate: Int = 1, visibility: Int = 7, compression: Boolean? = null, powerOfTwo: Boolean = true, autoplay: Boolean = false): Encoded =
        encode(listOf(mesh), texturePng, textureName, scale, offset, flipUv, noShadow, autoRotate, visibility, compression, powerOfTwo, autoplay)

    /**
     * Several meshes with the same faces make an animation: each is one frame, played over [duration] ticks (the number of frames when 0) and blended
     * with [easing] (0 none, 1 linear, 2 in-out cubic, 3 four point bezier). With [autoplay] the model plays by itself from the game time.
     */
    fun encode(
        meshes: List<ObjMesh>,
        texturePng: ByteArray,
        textureName: String,
        scale: Double = 1.0,
        offset: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
        flipUv: Boolean = false,
        noShadow: Boolean = false,
        autoRotate: Int = 1,
        visibility: Int = 7,
        compression: Boolean? = null,
        powerOfTwo: Boolean = true,
        autoplay: Boolean = false,
        duration: Int = 0,
        easing: Int = 3,
        interpolation: Int = 1,
    ): Encoded {
        val warnings = ArrayList<String>()
        if (meshes.isEmpty()) throw BbParseException("there is nothing to encode.")
        val frameCount = meshes.size
        val animationTicks = if (duration == 0) frameCount else duration
        if (animationTicks !in 1..65536) throw BbParseException("the animation must last between 1 and 65536 ticks.")
        val mesh = meshes.first()
        if (meshes.any { it.faces.size != mesh.faces.size }) throw BbParseException("every frame of an animation needs the same number of faces.")
        val texture = ImageIO.read(ByteArrayInputStream(texturePng)) ?: throw BbParseException("the texture is not an image.")
        val x = texture.width
        val y = texture.height
        if (x < 8) throw BbParseException("the texture must be at least 8 pixels wide.")

        // Index the vertices: each distinct position and uv is stored once, and a vertex is a pair of indexes.
        val positions = ArrayList<DoubleArray>()
        val uvs = ArrayList<DoubleArray>()
        val vertices = ArrayList<IntArray>()
        val positionIndex = HashMap<List<Double>, Int>()
        val uvIndex = HashMap<List<Double>, Int>()
        fun vertex(frame: ObjMesh, corner: IntArray) {
            val position = frame.positions[corner[0]]
            val uv = frame.uvs[corner[1]]
            val p = positionIndex.getOrPut(position.toList()) { positions.add(position); positions.size - 1 }
            val u = uvIndex.getOrPut(uv.toList()) { uvs.add(uv); uvs.size - 1 }
            vertices += intArrayOf(p, u)
        }
        for (frame in meshes) {
            for (face in frame.faces) {
                for (corner in face.take(4)) vertex(frame, corner)
                if (face.size == 3) vertex(frame, face[1])
                if (face.size > 4 && frame === mesh) warnings += "a face with more than four corners was cut down to four."
            }
        }
        val faces = mesh.faces.size

        val uvHeight = ceil(faces.toDouble() / x).toInt()
        val positionHeight = ceil(positions.size * 3.0 / x).toInt()
        val uvTableHeight = ceil(uvs.size * 2.0 / x).toInt()
        val compressed = compression ?: (uvs.size <= 255)
        if (compressed && uvs.size > 256) throw BbParseException("too many different texture coordinates to compress.")
        val vertexHeight = ceil(vertices.size * (if (compressed) 1 else 2) / x.toDouble()).toInt()
        var ty = 1 + uvHeight + y + positionHeight + uvTableHeight + vertexHeight
        if (powerOfTwo) ty = Integer.highestOneBit(ty - 1) shl 1
        if (ty > 4096 && x < 4096) warnings += "the data texture is $ty pixels tall: a wider texture or fewer faces makes it smaller."

        val out = BufferedImage(x, ty, BufferedImage.TYPE_INT_ARGB)
        fun put(px: Int, py: Int, r: Int, g: Int, b: Int, a: Int) {
            out.setRGB(px, py, (a and 255 shl 24) or (r and 255 shl 16) or (g and 255 shl 8) or (b and 255))
        }
        val nvertices = faces * 4
        val colorBehavior = (0 shl 6) + (1 shl 3) + 2 // pitch, yaw, roll as objmc.py numbers them
        put(0, 0, 12, 34, 56, if (compressed) 79 else 78)
        put(1, 0, x / 256, x % 256, y / 256, y % 256)
        put(2, 0, nvertices / 16777216 % 256, nvertices / 65536 % 256, nvertices / 256 % 256, nvertices % 256)
        put(3, 0, frameCount / 65536 % 256, frameCount / 256 % 256, frameCount % 256, 1) // the frames, and one texture
        put(4, 0, animationTicks / 65536 % 256, animationTicks / 256 % 256, animationTicks % 256, 128 + ((if (autoplay) 1 else 0) shl 6) + (easing shl 4) + (interpolation shl 2))
        put(5, 0, positionHeight / 256 % 256, positionHeight % 256, uvTableHeight / 256 % 256, uvTableHeight % 256)
        put(6, 0, ((if (noShadow) 1 else 0) shl 7) + (autoRotate shl 5) + (visibility shl 2) + colorBehavior / 256, colorBehavior % 256, 255, 255)

        // The texture itself, upside down unless flipUv.
        for (row in 0 until y) {
            val target = 1 + uvHeight + row
            val source = if (flipUv) row else y - 1 - row
            for (column in 0 until x) out.setRGB(column, target, texture.getRGB(column, source))
        }

        // One element per face, with the pixel in the header that tells the shader where the data starts.
        val elements = JsonArray()
        for (face in 0 until faces) {
            val posX = face % x
            val posY = floor(face.toDouble() / x).toInt() + 1
            put(posX, posY, posX / 256 % 256, posX % 256, posY / 256 % 256, posY % 256)
            elements.add(JsonObject().apply {
                add("from", JsonArray().apply { add(8); add(0); add(8) })
                add("to", JsonArray().apply { add(24); add(16); add(8) })
                add("faces", JsonObject().apply {
                    add("north", JsonObject().apply {
                        add("uv", JsonArray().apply {
                            add((posX + 0.1) * 16 / x); add((posY + 0.1) * 16 / ty); add((posX + 0.9) * 16 / x); add((posY + 0.9) * 16 / ty)
                        })
                        addProperty("texture", "#0")
                        addProperty("tintindex", 0)
                    })
                })
            })
        }

        // Positions: 8388608 + position in 1/65536 units, three bytes each.
        var base = 1 + uvHeight + y
        for ((i, position) in positions.withIndex()) {
            for (axis in 0 until 3) {
                val value = CENTER + position[axis] * 65536 * scale + offset[axis] * 65536
                val p = i * 3 + axis
                put(p % x, base + p / x, byteOf(value / 65536), byteOf(value / 256), byteOf(value), 255)
            }
        }
        base += positionHeight
        for ((i, uv) in uvs.withIndex()) {
            for (axis in 0 until 2) {
                val value = uv[axis] * 65535
                val p = i * 2 + axis
                put(p % x, base + p / x, byteOf(value / 65536), byteOf(value / 256), byteOf(value), 255)
            }
        }
        base += uvTableHeight
        for ((i, vertex) in vertices.withIndex()) {
            val (p, u) = vertex[0] to vertex[1]
            if (compressed) {
                put(i % x, base + i / x, p / 65536 % 256, p / 256 % 256, p % 256, u % 256 + 1)
            } else {
                put(2 * i % x, base + 2 * i / x, p / 65536 % 256, p / 256 % 256, p % 256, 255)
                put((2 * i + 1) % x, base + (2 * i + 1) / x, u / 65536 % 256, u / 256 % 256, u % 256, 255)
            }
        }

        val png = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
        val model = JsonObject().apply {
            add("textures", JsonObject().apply { addProperty("0", textureName) })
            add("elements", elements)
            add("display", JsonObject().apply {
                for (hand in listOf("thirdperson_righthand", "thirdperson_lefthand")) {
                    add(hand, JsonObject().apply { add("rotation", JsonArray().apply { add(85); add(0); add(0) }) })
                }
            })
        }
        return Encoded(png, model.toString(), faces, warnings)
    }

    /** Python's `int(v % 256)` for the byte of a fixed point number. */
    private fun byteOf(value: Double): Int = (((value % 256) + 256) % 256).toInt()
}
