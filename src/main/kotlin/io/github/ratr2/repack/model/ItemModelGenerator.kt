package io.github.ratr2.repack.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.abs

/** errors are problems that make the model unusable in the game, so it is left out of the pack. */
class GeneratedModel(val files: Map<String, ByteArray>, val warnings: List<String>, val errors: List<String> = emptyList())

object ItemModelGenerator {

    const val NAMESPACE = "repack"

    /** Vanilla accepts element coordinates between -16 and 32. */
    private const val MIN_COORDINATE = -16.0
    private const val MAX_COORDINATE = 32.0

    private val ANGLES = setOf(-45.0, -22.5, 0.0, 22.5, 45.0)

    /** Marks a model that needs objmc's shaders in the pack: one empty file per such model under this folder. */
    const val OBJMC_MARKER = "assets/$NAMESPACE/objmc/"

    /**
     * A model with meshes: the whole model goes into objmc's data texture and a model of flat quads, and objmc's core shaders (added to the
     * pack by the era files) build the real shape in the game. Needs one texture.
     */
    private fun generateMesh(id: String, model: BbModel, shelf: Boolean): GeneratedModel {
        val warnings = ArrayList<String>(model.warnings)
        warnings += "Meshes are drawn with objmc's technique: it needs 1.21.9 or newer clients for now, and a graphics card with OpenGL 4.5."
        val converted = try {
            MeshModel.convert(model)
        } catch (e: BbParseException) {
            return GeneratedModel(emptyMap(), warnings, listOf(e.message ?: "the mesh model could not be converted."))
        } catch (e: LinkageError) {
            return GeneratedModel(emptyMap(), warnings, listOf("this server's Java has no image support (the java.desktop module), which meshes need."))
        }
        val encoded = try {
            Objmc.encode(
                converted.mesh, converted.png, "$NAMESPACE:block/$id/data",
                // Blockbench units to blocks, and the model's own floor centre (8, 0, 8) to where objmc puts its origin.
                // Not compressed: the shader only recognises the marker of an uncompressed model (78), never the compressed one (79).
                scale = 1.0 / 16, offset = doubleArrayOf(-0.5, 0.0, -0.5), flipUv = true, compression = false,
            )
        } catch (e: BbParseException) {
            return GeneratedModel(emptyMap(), warnings, listOf(e.message ?: "the texture could not be used."))
        } catch (e: java.io.IOException) {
            return GeneratedModel(emptyMap(), warnings, listOf("the texture could not be read: ${e.message}"))
        } catch (e: LinkageError) {
            return GeneratedModel(emptyMap(), warnings, listOf("this server's Java has no image support (the java.desktop module), which meshes need."))
        }
        warnings += encoded.warnings
        val files = HashMap<String, ByteArray>()
        files["assets/$NAMESPACE/textures/block/$id/data.png"] = encoded.png
        val block = com.google.gson.JsonParser.parseString(encoded.modelJson).asJsonObject
        displayOf(model.display, warnings, shelf)?.let { block.add("display", it) }
        files["assets/$NAMESPACE/models/block/$id.json"] = block.toString().toByteArray(Charsets.UTF_8)
        files["assets/$NAMESPACE/items/$id.json"] = JsonObject().apply {
            add("model", JsonObject().apply {
                addProperty("type", "minecraft:model")
                addProperty("model", "$NAMESPACE:block/$id")
            })
        }.toString().toByteArray(Charsets.UTF_8)
        files["$OBJMC_MARKER$id.json"] = "{}".toByteArray(Charsets.UTF_8)

        // Each animation is its own model: the pose of the bones is sampled into frames, which objmc plays on its own from the game time.
        val taken = HashSet<String>()
        for (animation in model.animations) {
            val clip = ModelLibrary.cleanId(animation.name)
            if (!taken.add(clip)) {
                warnings += "Animation '${animation.name}' has the same name as another one once cleaned up ('$clip') and was skipped."
                continue
            }
            val ticks = Math.round(animation.length * 20).toInt().coerceIn(1, MAX_ANIMATION_TICKS)
            val frameCount = ticks.coerceIn(2, MAX_ANIMATION_FRAMES)
            val loops = animation.loop == "loop"
            if (!loops) warnings += "Animation '${animation.name}' is set to ${animation.loop}: objmc plays it as a loop."
            try {
                val frames = (0 until frameCount).map { k ->
                    val time = if (loops) animation.length * k / frameCount else animation.length * k / (frameCount - 1)
                    MeshModel.convert(model, AnimationSampler.sample(animation, time)).mesh
                }
                val clipped = Objmc.encode(
                    frames, converted.png, "$NAMESPACE:block/$id/anim/$clip",
                    scale = 1.0 / 16, offset = doubleArrayOf(-0.5, 0.0, -0.5), flipUv = true, compression = false,
                    autoplay = true, duration = ticks, easing = 1,
                )
                warnings += clipped.warnings.map { "animation $clip: $it" }
                files["assets/$NAMESPACE/textures/block/$id/anim/$clip.png"] = clipped.png
                val clipModel = com.google.gson.JsonParser.parseString(clipped.modelJson).asJsonObject
                displayOf(model.display, warnings, shelf)?.let { clipModel.add("display", it) }
                files["assets/$NAMESPACE/models/block/$id/anim/$clip.json"] = clipModel.toString().toByteArray(Charsets.UTF_8)
                files["assets/$NAMESPACE/items/$id/anim/$clip.json"] = JsonObject().apply {
                    add("model", JsonObject().apply {
                        addProperty("type", "minecraft:model")
                        addProperty("model", "$NAMESPACE:block/$id/anim/$clip")
                    })
                }.toString().toByteArray(Charsets.UTF_8)
            } catch (e: BbParseException) {
                warnings += "Animation '${animation.name}' was skipped: ${e.message}"
            }
        }
        return GeneratedModel(files, warnings)
    }

    /** An animation is sampled into at most this many frames, and lasts at most this many ticks (four minutes). */
    private const val MAX_ANIMATION_FRAMES = 120
    private const val MAX_ANIMATION_TICKS = 4800

    /** shelf keeps the `on_shelf` display setting, which only game versions from 1.21.9 know. */
    fun generate(id: String, model: BbModel, shelf: Boolean = true, mode: ObjmcMode = ObjmcMode.AUTO, canObjmc: Boolean = true): GeneratedModel {
        val reason = model.objmcReason()
        if (canObjmc && (mode == ObjmcMode.ALWAYS || (mode == ObjmcMode.AUTO && reason != null))) return generateMesh(id, model, shelf)
        val files = HashMap<String, ByteArray>()
        val warnings = ArrayList<String>(model.warnings + model.vanillaWarnings)
        if (reason != null) {
            warnings += when {
                !canObjmc -> "Clients up to 1.21.8 see a simplified version of this model, because $reason and they cannot run objmc."
                else -> "This model is drawn as a vanilla model (objmc: never), so what it needs is left out: $reason."
            }
        }
        if (model.meshes.isNotEmpty()) warnings += "${model.meshes.size} mesh element(s) were skipped."
        val errors = ArrayList<String>()

        val textures = JsonObject()
        for ((index, texture) in model.textures.withIndex()) {
            if (texture.png.isEmpty()) continue
            textures.addProperty(index.toString(), "$NAMESPACE:block/$id/$index")
            files["assets/$NAMESPACE/textures/block/$id/$index.png"] = texture.png
        }
        textures.keySet().firstOrNull()?.let { textures.addProperty("particle", textures.get(it).asString) }

        val elements = JsonArray()
        for (cube in model.cubes) {
            elementOf(cube, model.textures, textures, warnings, errors)?.let(elements::add)
        }

        val block = JsonObject().apply {
            add("textures", textures)
            add("elements", elements)
            displayOf(model.display, warnings, shelf)?.let { add("display", it) }
        }
        files["assets/$NAMESPACE/models/block/$id.json"] = block.toString().toByteArray(Charsets.UTF_8)

        val item = JsonObject().apply {
            add("model", JsonObject().apply {
                addProperty("type", "minecraft:model")
                addProperty("model", "$NAMESPACE:block/$id")
            })
        }
        files["assets/$NAMESPACE/items/$id.json"] = item.toString().toByteArray(Charsets.UTF_8)
        return GeneratedModel(files, warnings, errors)
    }

    /** Vanilla accepts translation between -80 and 80 and scale between -4 and 4 in a display transform. */
    private fun displayOf(display: Map<String, DisplayTransform>, warnings: MutableList<String>, shelf: Boolean): JsonObject? {
        if (display.isEmpty()) return null
        val result = JsonObject()
        for ((context, transform) in display) {
            if (context == "on_shelf" && !shelf) continue
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

    private fun elementOf(cube: BbCube, textures: List<BbTexture>, usable: JsonObject, warnings: MutableList<String>, errors: MutableList<String>): JsonObject? {
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
                errors += "Cube '${cube.name}' reaches outside the -16 to 32 range vanilla models allow, so the game would not load the model."
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
