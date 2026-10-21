package io.github.ratr2.repack.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.joml.Matrix4d
import org.joml.Quaternionf
import org.joml.Vector3d
import kotlin.math.abs

class RigBone(val name: String, val uuid: String?, val parent: Int, val pivot: Vec3, val rotation: Vec3, val cubes: List<BbCube>) {
    val hasModel: Boolean get() = cubes.isNotEmpty()
}

class BoneDisplay(val translation: Vec3, val rotation: Quaternionf, val scale: Vec3)

class RigClip(val key: String, val animation: BbAnimation)

/**
 * A model split into bones, the way BetterModel does it: every bone is a vanilla item model of its own, shown by its own item display, and an
 * animation or a rotated group is only a different transform for each display. Nothing but vanilla item models is needed in the pack.
 */
class Rig(val bones: List<RigBone>, val clips: List<RigClip>) {

    fun displays(pose: Map<String, BoneTransform> = emptyMap()): List<BoneDisplay> {
        val worlds = arrayOfNulls<Matrix4d>(bones.size)
        return bones.mapIndexed { index, bone ->
            val parentWorld = if (bone.parent >= 0) worlds[bone.parent]!! else Matrix4d()
            val parentPivot = if (bone.parent >= 0) bones[bone.parent].pivot else CENTER
            val move = bone.uuid?.let(pose::get) ?: BoneTransform.NONE
            val local = Matrix4d()
                .translate(bone.pivot.x - parentPivot.x, bone.pivot.y - parentPivot.y, bone.pivot.z - parentPivot.z)
                .translate(move.position.x, move.position.y, move.position.z)
            rotate(local, bone.rotation + move.rotation)
            local.scale(move.scale.x, move.scale.y, move.scale.z)
            val world = Matrix4d(parentWorld).mul(local)
            worlds[index] = world
            val position = world.transformPosition(Vector3d())
            val scale = world.getScale(Vector3d())
            val turn = world.getNormalizedRotation(org.joml.Quaterniond())
            BoneDisplay(
                Vec3(position.x / 16, position.y / 16, position.z / 16),
                Quaternionf(turn.x.toFloat(), turn.y.toFloat(), turn.z.toFloat(), turn.w.toFloat()),
                Vec3(scale.x, scale.y, scale.z),
            )
        }
    }

    fun clip(key: String): RigClip? = clips.firstOrNull { it.key == key }

    fun toJson(): JsonObject = JsonObject().apply {
        add("bones", JsonArray().also { array ->
            for (bone in bones) array.add(JsonObject().apply {
                addProperty("name", bone.name)
                bone.uuid?.let { addProperty("uuid", it) }
                addProperty("parent", bone.parent)
                add("pivot", bone.pivot.toJson())
                add("rotation", bone.rotation.toJson())
                addProperty("model", bone.hasModel)
            })
        })
        add("clips", JsonArray().also { array ->
            for (clip in clips) array.add(JsonObject().apply {
                addProperty("key", clip.key)
                addProperty("name", clip.animation.name)
                addProperty("length", clip.animation.length)
                addProperty("loop", clip.animation.loop)
                add("animators", JsonObject().also { animators ->
                    for ((uuid, keyframes) in clip.animation.animators) animators.add(uuid, JsonArray().also { list ->
                        for (k in keyframes) list.add(JsonObject().apply {
                            addProperty("channel", k.channel)
                            addProperty("time", k.time)
                            add("before", k.before.toJson())
                            add("after", k.after.toJson())
                            addProperty("interpolation", k.interpolation)
                        })
                    })
                })
            })
        })
    }

    companion object {
        /** Blockbench coordinates are measured from the corner of the block; displays from the centre of the model, which is (8, 8, 8). */
        val CENTER = Vec3(8.0, 8.0, 8.0)

        fun fromJson(text: String): Rig {
            val root = JsonParser.parseString(text).asJsonObject
            val bones = root.getAsJsonArray("bones").map { element ->
                val o = element.asJsonObject
                RigBone(
                    o.get("name").asString, o.get("uuid")?.asString, o.get("parent").asInt, vec(o.getAsJsonArray("pivot")), vec(o.getAsJsonArray("rotation")),
                    if (o.get("model").asBoolean) listOf(PLACEHOLDER) else emptyList(),
                )
            }
            val clips = root.getAsJsonArray("clips").map { element ->
                val o = element.asJsonObject
                val animators = o.getAsJsonObject("animators").entrySet().associate { (uuid, list) ->
                    uuid to list.asJsonArray.map { k ->
                        val key = k.asJsonObject
                        BbKeyframe(key.get("channel").asString, key.get("time").asDouble, vec(key.getAsJsonArray("before")), vec(key.getAsJsonArray("after")), key.get("interpolation").asString)
                    }
                }
                RigClip(o.get("key").asString, BbAnimation(o.get("name").asString, o.get("length").asDouble, o.get("loop").asString, animators))
            }
            return Rig(bones, clips)
        }

        private val PLACEHOLDER = BbCube("", Vec3.ZERO, Vec3.ZERO, 0.0, Vec3.ZERO, Vec3.ZERO, true, 0, emptyMap())

        private fun vec(array: JsonArray) = Vec3(array[0].asDouble, array[1].asDouble, array[2].asDouble)

        private fun Vec3.toJson() = JsonArray().also { it.add(x); it.add(y); it.add(z) }

        /** Blockbench's rotation: degrees around x, then y, then z. */
        internal fun rotate(matrix: Matrix4d, degrees: Vec3) {
            if (degrees == Vec3.ZERO) return
            matrix.rotateZ(Math.toRadians(degrees.z)).rotateY(Math.toRadians(degrees.y)).rotateX(Math.toRadians(degrees.x))
        }

        fun clipKey(name: String): String = name.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
    }
}

object RigBuilder {

    private val ANGLES = setOf(-45.0, -22.5, 22.5, 45.0)

    fun needed(model: BbModel): Boolean =
        model.animations.any { it.animators.isNotEmpty() } ||
            model.cubes.any { cube -> cube.path.any { it.rotation != Vec3.ZERO } || !vanillaRotation(cube.rotation) || reachesOutside(cube) }

    fun build(model: BbModel): Rig {
        val bones = ArrayList<RigBone>()
        val cubes = ArrayList<MutableList<BbCube>>()
        val byChain = HashMap<List<String>, Int>()
        var uniqueGroup = 0
        fun boneFor(path: List<GroupTransform>): Int {
            if (path.isEmpty()) return ROOT
            val chain = path.map { it.uuid ?: "#${uniqueGroup++}" }
            byChain[chain]?.let { return it }
            val parent = boneFor(path.dropLast(1))
            val group = path.last()
            bones += RigBone(group.name.ifEmpty { "bone${bones.size}" }, group.uuid, parent, group.origin, group.rotation, emptyList())
            cubes += ArrayList<BbCube>()
            return (bones.size - 1).also { byChain[chain] = it }
        }
        // The root holds what is in no group. It is first, so the others can name it as their parent.
        bones += RigBone("root", null, -1, Rig.CENTER, Vec3.ZERO, emptyList())
        cubes += ArrayList<BbCube>()

        for (cube in model.cubes) {
            val parent = boneFor(cube.path)
            if (vanillaRotation(cube.rotation)) {
                cubes[parent] += cube
            } else {
                // A rotation vanilla cannot draw becomes a bone of its own, turned around the cube's own origin.
                bones += RigBone(cube.name.ifEmpty { "cube${bones.size}" }, null, parent, cube.origin, cube.rotation, emptyList())
                cubes += arrayListOf(cube.withoutRotation())
            }
        }
        val done = bones.mapIndexed { i, b -> RigBone(b.name, b.uuid, b.parent, b.pivot, b.rotation, cubes[i]) }
        val clips = model.animations.filter { it.animators.isNotEmpty() }.map { RigClip(Rig.clipKey(it.name), it) }
        return Rig(done, clips)
    }

    private const val ROOT = 0

    private fun BbCube.withoutRotation() = BbCube(name, from, to, inflate, origin, Vec3.ZERO, shade, lightEmission, faces, path)

    private fun vanillaRotation(rotation: Vec3): Boolean {
        val turned = listOf(rotation.x, rotation.y, rotation.z).filter { abs(it) > 1e-4 }
        return turned.isEmpty() || (turned.size == 1 && turned[0] in ANGLES)
    }

    private fun reachesOutside(cube: BbCube): Boolean =
        listOf(cube.from.x - cube.inflate, cube.from.y - cube.inflate, cube.from.z - cube.inflate, cube.to.x + cube.inflate, cube.to.y + cube.inflate, cube.to.z + cube.inflate).any { it < -16.0 || it > 32.0 }
}
