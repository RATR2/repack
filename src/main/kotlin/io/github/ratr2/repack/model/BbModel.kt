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

/** [uv] is x1, y1, x2, y2 in the texture's own UV space (see [BbTexture.uvWidth]). */
class BbFace(val uv: List<Double>, val rotation: Int, val texture: Int)

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
)

class BbTexture(val name: String, val png: ByteArray, val uvWidth: Int, val uvHeight: Int)

/**
 * A parsed .bbmodel, reduced to what a static block needs: cubes and textures.
 * [warnings] lists things in the file that the plugin ignores or cannot represent.
 */
class BbModel(
    val name: String,
    val textures: List<BbTexture>,
    val cubes: List<BbCube>,
    val warnings: List<String>,
)

class BbParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
