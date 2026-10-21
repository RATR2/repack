package io.github.ratr2.repack.model

import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a whole .bbmodel (its cubes, its meshes and the groups they sit in) into one [ObjMesh] in Blockbench units, ready for [Objmc].
 * Every cube becomes six quads. Every transform is applied, so rotations that a vanilla model cannot have (any angle, on any axis, in nested
 * groups) are exact. Only one texture is supported, since objmc stores a single one.
 */
object MeshModel {

    /** The mesh, and the model's textures packed into one image ([png]), since objmc stores a single one. */
    class Result(val mesh: ObjMesh, val png: ByteArray)

    private class Atlas(val png: ByteArray, val width: Int, val height: Int, val offsets: Map<Int, Int>, val sizes: Map<Int, Pair<Int, Int>>)

    /** The textures that are used, one above the other in an image as wide as the widest (and at least 8 pixels, which objmc needs). */
    private fun pack(model: BbModel, used: Set<Int>): Atlas {
        val images = used.associateWith { index ->
            javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(model.textures[index].png)) ?: throw BbParseException("the texture '${model.textures[index].name}' is not an image.")
        }
        val width = maxOf(8, images.values.maxOf { it.width })
        val height = images.values.sumOf { it.height }
        val out = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val offsets = HashMap<Int, Int>()
        val sizes = HashMap<Int, Pair<Int, Int>>()
        var y = 0
        for ((index, image) in images) {
            for (row in 0 until image.height) for (column in 0 until image.width) out.setRGB(column, y + row, image.getRGB(column, row))
            offsets[index] = y
            sizes[index] = image.width to image.height
            y += image.height
        }
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(out, "png", it) }.toByteArray()
        return Atlas(bytes, width, height, offsets, sizes)
    }

    private class Face(val corners: List<Vec3>, val uvs: List<DoubleArray>, val texture: Int)

    /** The model as one mesh. With a [pose], bones that have a transform in it (by group uuid) are moved, turned and scaled first. */
    fun convert(model: BbModel, pose: Map<String, BoneTransform> = emptyMap()): Result {
        val faces = ArrayList<Face>()
        for (cube in model.cubes) faces += cubeFaces(cube, pose)
        for (mesh in model.meshes) faces += meshFaces(mesh, pose)
        if (faces.isEmpty()) throw BbParseException("the model has no faces to draw.")
        val used = faces.map { it.texture }.toSortedSet()
        for (index in used) {
            val texture = model.textures.getOrNull(index) ?: throw BbParseException("the texture $index does not exist.")
            if (texture.png.isEmpty()) throw BbParseException("the texture '${texture.name}' is not embedded in the file.")
        }
        val atlas = pack(model, used)

        val positions = ArrayList<DoubleArray>()
        val uvs = ArrayList<DoubleArray>()
        val out = ArrayList<List<IntArray>>()
        for (face in faces) {
            val texture = model.textures[face.texture]
            val (width, height) = atlas.sizes.getValue(face.texture)
            val top = atlas.offsets.getValue(face.texture)
            // Fan the corners into triangles when there are more than four, which is all objmc can draw.
            val groups = if (face.corners.size <= 4) listOf(face.corners.indices.toList()) else (1 until face.corners.size - 1).map { listOf(0, it, it + 1) }
            for (group in groups) {
                out += group.map { i ->
                    val p = face.corners[i]
                    positions += doubleArrayOf(p.x, p.y, p.z)
                    // The point in the texture's own UV space, as a fraction of that texture, then as a fraction of the packed image.
                    uvs += doubleArrayOf(face.uvs[i][0] / texture.uvWidth * width / atlas.width, (top + face.uvs[i][1] / texture.uvHeight * height) / atlas.height)
                    intArrayOf(positions.size - 1, uvs.size - 1)
                }
            }
        }
        return Result(ObjMesh(positions, uvs, out), atlas.png)
    }

    // The corners of each side, in the order and with the winding of a vanilla block model, and the UV corner each one takes.
    private fun cubeFaces(cube: BbCube, pose: Map<String, BoneTransform>): List<Face> {
        val x1 = cube.from.x - cube.inflate
        val y1 = cube.from.y - cube.inflate
        val z1 = cube.from.z - cube.inflate
        val x2 = cube.to.x + cube.inflate
        val y2 = cube.to.y + cube.inflate
        val z2 = cube.to.z + cube.inflate
        val sides = mapOf(
            Direction.DOWN to listOf(Vec3(x1, y1, z2), Vec3(x1, y1, z1), Vec3(x2, y1, z1), Vec3(x2, y1, z2)),
            Direction.UP to listOf(Vec3(x1, y2, z1), Vec3(x1, y2, z2), Vec3(x2, y2, z2), Vec3(x2, y2, z1)),
            Direction.NORTH to listOf(Vec3(x2, y2, z1), Vec3(x2, y1, z1), Vec3(x1, y1, z1), Vec3(x1, y2, z1)),
            Direction.SOUTH to listOf(Vec3(x1, y2, z2), Vec3(x1, y1, z2), Vec3(x2, y1, z2), Vec3(x2, y2, z2)),
            Direction.WEST to listOf(Vec3(x1, y2, z1), Vec3(x1, y1, z1), Vec3(x1, y1, z2), Vec3(x1, y2, z2)),
            Direction.EAST to listOf(Vec3(x2, y2, z2), Vec3(x2, y1, z2), Vec3(x2, y1, z1), Vec3(x2, y2, z1)),
        )
        val result = ArrayList<Face>()
        for ((direction, corners) in sides) {
            val face = cube.faces[direction] ?: continue
            val (u1, v1, u2, v2) = face.uv
            val base = listOf(doubleArrayOf(u1, v1), doubleArrayOf(u1, v2), doubleArrayOf(u2, v2), doubleArrayOf(u2, v1))
            val shift = Math.floorMod(face.rotation / 90, 4)
            val uvs = List(4) { base[(it + shift) % 4] }
            val moved = corners.map { place(rotate(it, cube.origin, cube.rotation), cube.path, pose) }
            result += Face(moved, uvs, face.texture)
        }
        return result
    }

    private fun meshFaces(mesh: BbMesh, pose: Map<String, BoneTransform>): List<Face> = mesh.faces.map { face ->
        Face(face.corners.map { place(rotate(mesh.origin + it, mesh.origin, mesh.rotation), mesh.path, pose) }, face.uvs, face.texture)
    }

    /** Through the groups the element sits in, the innermost first. */
    private fun place(point: Vec3, path: List<GroupTransform>, pose: Map<String, BoneTransform>): Vec3 =
        path.asReversed().fold(point) { p, group ->
            val bone = group.uuid?.let(pose::get)
            if (bone == null) {
                rotate(p, group.origin, group.rotation)
            } else {
                // Scale about the pivot, turn by the bone's own rotation plus the animation's, then move.
                val scaled = Vec3(group.origin.x + (p.x - group.origin.x) * bone.scale.x, group.origin.y + (p.y - group.origin.y) * bone.scale.y, group.origin.z + (p.z - group.origin.z) * bone.scale.z)
                rotate(scaled, group.origin, group.rotation + bone.rotation) + bone.position
            }
        }

    /** [point] turned about [pivot] by Blockbench's rotation: degrees around x, then y, then z. */
    internal fun rotate(point: Vec3, pivot: Vec3, degrees: Vec3): Vec3 {
        if (degrees == Vec3.ZERO) return point
        val rx = Math.toRadians(degrees.x)
        val ry = Math.toRadians(degrees.y)
        val rz = Math.toRadians(degrees.z)
        var x = point.x - pivot.x
        var y = point.y - pivot.y
        var z = point.z - pivot.z
        var t = y * cos(rx) - z * sin(rx)
        z = y * sin(rx) + z * cos(rx)
        y = t
        t = x * cos(ry) + z * sin(ry)
        z = -x * sin(ry) + z * cos(ry)
        x = t
        t = x * cos(rz) - y * sin(rz)
        y = x * sin(rz) + y * cos(rz)
        x = t
        return Vec3(x + pivot.x, y + pivot.y, z + pivot.z)
    }
}
