package io.github.ratr2.repack.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Reads a Blockbench .bbmodel file (format version 4.x and 5.x). Blockbench's JSON layout is documented by the files
 * themselves; the element and outliner handling here follows the same model as BetterModel (MIT, toxicity188), see NOTICE.
 */
object BbParser {

    /** The display contexts Blockbench can set that a block model uses. `on_shelf` only exists from Minecraft 1.21.9, so the generator drops it for older packs. */
    val DISPLAY_CONTEXTS = listOf(
        "thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "gui", "head", "ground", "fixed",
        "on_shelf",
    )

    fun parse(name: String, json: String): BbModel {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw BbParseException("Not a valid .bbmodel JSON file: ${e.message}", e)
        }
        val warnings = ArrayList<String>()

        val resolution = root.obj("resolution")
        val resolutionWidth = resolution?.int("width", 16) ?: 16
        val resolutionHeight = resolution?.int("height", 16) ?: 16

        val textures = parseTextures(root, resolutionWidth, resolutionHeight, warnings)
        val groupRotations = groupRotations(root)
        val rotatedGroups = rotatedGroupsOf(root, groupRotations)

        val cubes = ArrayList<BbCube>()
        val elements = root.array("elements") ?: JsonArray()
        for (element in elements) {
            val obj = element.asJsonObject
            val type = obj.string("type") ?: "cube"
            val label = obj.string("name") ?: obj.string("uuid") ?: type
            if (type != "cube") {
                warnings += "Element '$label' is a $type, which is not supported yet and was skipped."
                continue
            }
            if (obj.bool("export", true).not() || obj.bool("visibility", true).not()) continue
            val uuid = obj.string("uuid")
            if (uuid != null && uuid in rotatedGroups) {
                warnings += "Cube '$label' sits in a rotated group (${rotatedGroups[uuid]}); group rotation is ignored."
            }
            cubes += parseCube(obj, label, textures.size, warnings)
        }

        if (cubes.isEmpty()) warnings += "The model has no cubes."
        return BbModel(name, textures, cubes, parseDisplay(root), warnings)
    }

    /** The Display tab. Names a block model never uses are left out, and a name an older client does not know would stop its model loading, which is what ItemModelGenerator guards against for `on_shelf`. */
    private fun parseDisplay(root: JsonObject): Map<String, DisplayTransform> {
        val display = root.obj("display") ?: return emptyMap()
        val result = LinkedHashMap<String, DisplayTransform>()
        for (context in DISPLAY_CONTEXTS) {
            val entry = display.obj(context) ?: continue
            result[context] = DisplayTransform(entry.numbers("rotation"), entry.numbers("translation"), entry.numbers("scale"))
        }
        return result
    }

    private fun JsonObject.numbers(key: String): List<Double>? {
        val array = array(key) ?: return null
        if (array.size() < 3) return null
        return List(3) { array[it].asDouble }
    }

    private fun parseTextures(root: JsonObject, width: Int, height: Int, warnings: MutableList<String>): List<BbTexture> {
        val result = ArrayList<BbTexture>()
        for ((index, element) in (root.array("textures") ?: JsonArray()).withIndex()) {
            val obj = element.asJsonObject
            val name = (obj.string("name") ?: "texture$index").substringBeforeLast('.')
            val source = obj.string("source")
            if (source == null || !source.startsWith("data:")) {
                warnings += "Texture '$name' is not embedded in the file; save the model with embedded textures."
                result += BbTexture(name, ByteArray(0), width, height)
                continue
            }
            val png = try {
                Base64.getDecoder().decode(source.substringAfter(','))
            } catch (e: IllegalArgumentException) {
                throw BbParseException("Texture '$name' has invalid base64 data", e)
            }
            if (obj.int("frame_time", 0) > 0 && obj.int("height", 0) > obj.int("width", 0)) {
                warnings += "Texture '$name' looks animated; animation is not generated yet."
            }
            result += BbTexture(name, png, obj.int("uv_width", width), obj.int("uv_height", height))
        }
        return result
    }

    private fun parseCube(obj: JsonObject, label: String, textureCount: Int, warnings: MutableList<String>): BbCube {
        val faces = LinkedHashMap<Direction, BbFace>()
        val faceObj = obj.obj("faces")
        for (direction in Direction.entries) {
            val face = faceObj?.obj(direction.key) ?: continue
            val texture = face.get("texture")
            // A face without a numeric texture index has no texture and is not drawn.
            if (texture == null || !texture.isJsonPrimitive || !texture.asJsonPrimitive.isNumber) continue
            val index = texture.asInt
            if (index !in 0 until textureCount) {
                warnings += "Cube '$label' face ${direction.key} uses texture $index, which does not exist."
                continue
            }
            val uv = face.array("uv")?.map { it.asDouble } ?: continue
            if (uv.size != 4) continue
            faces[direction] = BbFace(uv, face.int("rotation", 0), index)
        }
        return BbCube(
            name = label,
            from = obj.vec3("from"),
            to = obj.vec3("to"),
            inflate = obj.double("inflate", 0.0),
            origin = obj.vec3("origin"),
            rotation = obj.vec3("rotation"),
            shade = obj.bool("shade", true),
            lightEmission = obj.int("light_emission", 0),
            faces = faces,
        )
    }

    /** Group uuid to rotation, from Blockbench 5's top-level `groups` list. */
    private fun groupRotations(root: JsonObject): Map<String, Vec3> {
        val result = HashMap<String, Vec3>()
        for (group in root.array("groups") ?: JsonArray()) {
            val obj = group as? JsonObject ?: continue
            val uuid = obj.string("uuid") ?: continue
            result[uuid] = obj.vec3("rotation")
        }
        return result
    }

    /** Cube uuid to a description of the rotated group it sits under, for every cube below a group that has a rotation. */
    private fun rotatedGroupsOf(root: JsonObject, rotations: Map<String, Vec3>): Map<String, String> {
        val result = HashMap<String, String>()

        fun visit(node: JsonElement, rotatedAncestor: String?) {
            if (node.isJsonPrimitive) {
                if (rotatedAncestor != null) result[node.asString] = rotatedAncestor
                return
            }
            val obj = node.asJsonObject
            val uuid = obj.string("uuid")
            // Older files carry the rotation on the outliner node itself, newer ones in the top-level groups list.
            val rotation = if (obj.has("rotation")) obj.vec3("rotation") else uuid?.let(rotations::get) ?: Vec3.ZERO
            val rotated = rotation != Vec3.ZERO
            val here = if (rotated) "${obj.string("name") ?: uuid} ${rotation.x}, ${rotation.y}, ${rotation.z}" else rotatedAncestor
            for (child in obj.array("children") ?: JsonArray()) visit(child, here)
        }

        for (node in root.array("outliner") ?: JsonArray()) visit(node, null)
        return result
    }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.int(key: String, default: Int): Int = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default
    private fun JsonObject.double(key: String, default: Double): Double = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: default
    private fun JsonObject.bool(key: String, default: Boolean): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

    private fun JsonObject.vec3(key: String): Vec3 {
        val array = array(key) ?: return Vec3.ZERO
        if (array.size() < 3) return Vec3.ZERO
        return Vec3(array[0].asDouble, array[1].asDouble, array[2].asDouble)
    }
}
