package io.github.ratr2.repack.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.abs

/** Pack files for one model, plus anything that could not be represented. */
class GeneratedModel(val files: Map<String, ByteArray>, val warnings: List<String>)

/**
 * Turns a [BbModel] into a vanilla block model and an item model definition (`assets/<ns>/items/<id>.json`), which is what an
 * item display shows. Needs 1.21.4 or newer for the item model definition.
 */
object ItemModelGenerator {

    const val NAMESPACE = "repack"

    /** Vanilla accepts element coordinates between -16 and 32. */
    private const val MIN_COORDINATE = -16.0
    private const val MAX_COORDINATE = 32.0

    private val ANGLES = setOf(-45.0, -22.5, 0.0, 22.5, 45.0)

    fun generate(id: String, model: BbModel): GeneratedModel {
        val files = HashMap<String, ByteArray>()
        val warnings = ArrayList<String>(model.warnings)

        val textures = JsonObject()
        for ((index, texture) in model.textures.withIndex()) {
            if (texture.png.isEmpty()) continue
            textures.addProperty(index.toString(), "$NAMESPACE:block/$id/$index")
            files["assets/$NAMESPACE/textures/block/$id/$index.png"] = texture.png
        }
        textures.keySet().firstOrNull()?.let { textures.addProperty("particle", textures.get(it).asString) }

        val elements = JsonArray()
        for (cube in model.cubes) {
            elementOf(cube, model.textures, textures, warnings)?.let(elements::add)
        }

        val block = JsonObject().apply {
            add("textures", textures)
            add("elements", elements)
            displayOf(model.display, warnings)?.let { add("display", it) }
        }
        files["assets/$NAMESPACE/models/block/$id.json"] = block.toString().toByteArray(Charsets.UTF_8)

        val item = JsonObject().apply {
            add("model", JsonObject().apply {
                addProperty("type", "minecraft:model")
                addProperty("model", "$NAMESPACE:block/$id")
            })
        }
        files["assets/$NAMESPACE/items/$id.json"] = item.toString().toByteArray(Charsets.UTF_8)
        return GeneratedModel(files, warnings)
    }

    /** Vanilla accepts translation between -80 and 80 and scale between -4 and 4 in a display transform. */
    private fun displayOf(display: Map<String, DisplayTransform>, warnings: MutableList<String>): JsonObject? {
        if (display.isEmpty()) return null
        val result = JsonObject()
        for ((context, transform) in display) {
            val entry = JsonObject()
            transform.rotation?.let { entry.add("rotation", it.toJsonArray()) }
            transform.translation?.let { values ->
                val clamped = values.map { it.coerceIn(-80.0, 80.0) }
                if (clamped != values) warnings += "Display '$context' moves the model further than the 80 units vanilla allows; clamped."
                entry.add("translation", clamped.toJsonArray())
            }
            transform.scale?.let { values ->
                val clamped = values.map { it.coerceIn(-4.0, 4.0) }
                if (clamped != values) warnings += "Display '$context' scales the model beyond 4 times, which vanilla does not allow; clamped."
                entry.add("scale", clamped.toJsonArray())
            }
            if (entry.size() > 0) result.add(context, entry)
        }
        return result.takeIf { it.size() > 0 }
    }

    private fun List<Double>.toJsonArray() = JsonArray().also { array -> forEach { array.add(it) } }

    private fun elementOf(cube: BbCube, textures: List<BbTexture>, usable: JsonObject, warnings: MutableList<String>): JsonObject? {
        val faces = JsonObject()
        for ((direction, face) in cube.faces) {
            val texture = textures[face.texture]
            if (!usable.has(face.texture.toString())) continue
            val u = 16.0 / texture.uvWidth
            val v = 16.0 / texture.uvHeight
            faces.add(direction.key, JsonObject().apply {
                add("uv", JsonArray().apply {
                    add(face.uv[0] * u); add(face.uv[1] * v); add(face.uv[2] * u); add(face.uv[3] * v)
                })
                addProperty("texture", "#${face.texture}")
                if (face.rotation != 0) addProperty("rotation", face.rotation)
            })
        }
        if (faces.size() == 0) return null

        val from = cube.from - Vec3(cube.inflate, cube.inflate, cube.inflate)
        val to = cube.to + Vec3(cube.inflate, cube.inflate, cube.inflate)
        for (value in listOf(from.x, from.y, from.z, to.x, to.y, to.z)) {
            if (value < MIN_COORDINATE || value > MAX_COORDINATE) {
                warnings += "Cube '${cube.name}' reaches outside the -16 to 32 range vanilla models allow and will not load."
                break
            }
        }

        return JsonObject().apply {
            add("from", from.toJson())
            add("to", to.toJson())
            rotationOf(cube, warnings)?.let { add("rotation", it) }
            if (!cube.shade) addProperty("shade", false)
            if (cube.lightEmission > 0) addProperty("light_emission", cube.lightEmission.coerceAtMost(15))
            add("faces", faces)
        }
    }

    private fun rotationOf(cube: BbCube, warnings: MutableList<String>): JsonObject? {
        val angles = listOf("x" to cube.rotation.x, "y" to cube.rotation.y, "z" to cube.rotation.z).filter { abs(it.second) > 1e-4 }
        if (angles.isEmpty()) return null
        val (axis, angle) = angles.singleOrNull()
            ?: return null.also { warnings += "Cube '${cube.name}' rotates around several axes, which vanilla models cannot do; rotation ignored." }
        if (angle !in ANGLES) {
            warnings += "Cube '${cube.name}' rotates $angle degrees, but vanilla only allows -45, -22.5, 22.5 and 45; rotation ignored."
            return null
        }
        return JsonObject().apply {
            add("origin", cube.origin.toJson())
            addProperty("axis", axis)
            addProperty("angle", angle)
        }
    }

    private fun Vec3.toJson() = JsonArray().apply { add(x); add(y); add(z) }
}
