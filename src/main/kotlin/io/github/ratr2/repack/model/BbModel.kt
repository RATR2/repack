package io.github.ratr2.repack.model

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

enum class Direction(val key: String) {
    NORTH("north"),
    EAST("east"),
    SOUTH("south"),
    WEST("west"),
    UP("up"),
    DOWN("down"),
}

/** uv is x1, y1, x2, y2 in the texture's own UV space (see BbTexture.uvWidth). */
class BbFace(val uv: List<Double>, val rotation: Int, val texture: Int)

class GroupTransform(val origin: Vec3, val rotation: Vec3, val uuid: String? = null, val name: String = "")

class BbCube(
    val name: String,
    val from: Vec3,
    val to: Vec3,
    val inflate: Double,
    val origin: Vec3,
    val rotation: Vec3,
    val shade: Boolean,
    val lightEmission: Int,
    val faces: Map<Direction, BbFace>,
    val path: List<GroupTransform> = emptyList(),
)

/** One face of a mesh: its corners in order (three or more), each with a point in the texture's UV space. */
class BbMeshFace(val corners: List<Vec3>, val uvs: List<DoubleArray>, val texture: Int)

/** A Blockbench mesh element: free faces, which a vanilla block model cannot show. [faces] are already in the element's own space, before [origin] and [rotation]. */
class BbMesh(
    val name: String,
    val origin: Vec3,
    val rotation: Vec3,
    val faces: List<BbMeshFace>,
    val path: List<GroupTransform> = emptyList(),
)

/** One entry of Blockbench's Display tab. A missing part means no change: no rotation, no translation, scale 1. */
class DisplayTransform(val rotation: List<Double>?, val translation: List<Double>?, val scale: List<Double>?)

class BbTexture(val name: String, val png: ByteArray, val uvWidth: Int, val uvHeight: Int)

class BbModel(
    val name: String,
    val textures: List<BbTexture>,
    val cubes: List<BbCube>,
    val display: Map<String, DisplayTransform>,
    val warnings: List<String>,
    /** Mesh elements. A model with any is shown with objmc's technique (see Objmc and MeshModel) instead of as a vanilla model. */
    val meshes: List<BbMesh> = emptyList(),
    /** The animations, by what Blockbench calls them. Only the objmc path plays them, baked into frames. */
    val animations: List<BbAnimation> = emptyList(),
    val vanillaWarnings: List<String> = emptyList(),
) {
    /** Why this model cannot be a vanilla block model without losing something, or null when it can. */
    fun objmcReason(): String? = when {
        meshes.isNotEmpty() -> "it has meshes"
        animations.any { it.animators.isNotEmpty() } -> "it has animations"
        cubes.any { cube -> cube.path.any { it.rotation != Vec3.ZERO } } -> "a group is rotated"
        cubes.any { !vanillaRotation(it.rotation) } -> "a cube is rotated in a way a vanilla model cannot be"
        cubes.any { it.reachesOutside() } -> "a cube reaches outside what a vanilla model allows"
        else -> null
    }

    private fun vanillaRotation(rotation: Vec3): Boolean {
        val turned = listOf(rotation.x, rotation.y, rotation.z).filter { it != 0.0 }
        return turned.isEmpty() || (turned.size == 1 && turned[0] in setOf(-45.0, -22.5, 22.5, 45.0))
    }

    private fun BbCube.reachesOutside(): Boolean =
        listOf(from.x - inflate, from.y - inflate, from.z - inflate, to.x + inflate, to.y + inflate, to.z + inflate).any { it < -16.0 || it > 32.0 }
}

class BbParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
