package io.github.ratr2.repack.sky

sealed interface SkySource {
    class Texture(val png: ByteArray, val width: Int, val height: Int) : SkySource

    class Shader(val code: String) : SkySource
}

/**
 * One skybox, read from `skyboxes/<id>.yml`. A skybox shows for a player when everything in dimensions, worlds and biomes that is not empty matches where
 * they are (and they have the permission, if there is one); of those the one with the highest priority shows, and a plain tie goes to the one that names more.
 * code is what the shader recognises it by (1 to SkyboxDefinitions.MAX_SKYBOXES), handed out by id.
 */
class SkyboxDefinition(
    val id: String,
    val source: SkySource,
    val priority: Int,
    /** How much the fog of the game covers it: 0 not at all, 1 like any far thing. */
    val fog: Double,
    val dimensions: Set<String>,
    val worlds: Set<String>,
    val biomes: Set<String>,
    val permission: String?,
    val code: Int,
) {
    val specificity: Int get() = listOf(dimensions, worlds, biomes).count { it.isNotEmpty() } + if (permission != null) 1 else 0

    fun matches(dimension: String, world: String, biome: String): Boolean =
        (dimensions.isEmpty() || dimension in dimensions) && (worlds.isEmpty() || world in worlds) && (biomes.isEmpty() || biome in biomes)
}
