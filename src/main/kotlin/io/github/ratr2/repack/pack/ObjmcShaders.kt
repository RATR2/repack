package io.github.ratr2.repack.pack

/**
 * The core shaders of objmc (https://github.com/Godlander/objmc, MIT, see NOTICE) that rebuild a mesh stored in a texture. They replace the game's
 * entity and item shaders, so they go into the pack only when a model has meshes.
 *
 * objmc's own are written for 1.21.11, and the game's versions of those shaders are the same in 1.21.9 and 1.21.10, so those three use them as they are.
 * 26.1, 26.2 and 26.3 changed the shaders (a separate item shader, new variable names and defines, and in 26.3 explicit locations, `#include`, the
 * vertex index and order independent transparency), so the same changes were put onto their vanilla shaders by `.dev/tools/port-objmc.py`.
 */
object ObjmcShaders {

    private val ORIGINAL = setOf("1.21.10", "1.21.11")
    private val PORTED = setOf("26.1", "26.2", "26.3")

    private val INCLUDES = listOf("objmc_main.glsl", "objmc_tools.glsl", "objmc_light.glsl")
    private val ORIGINAL_CORE = listOf("entity.vsh", "entity.fsh", "rendertype_item_entity_translucent_cull.vsh", "rendertype_item_entity_translucent_cull.fsh")
    private val PORTED_CORE = listOf("entity.vsh", "entity.fsh", "item.vsh", "item.fsh")

    fun supports(era: PackEra) = era.id in ORIGINAL || era.id in PORTED

    private fun read(path: String) = ObjmcShaders::class.java.getResourceAsStream(path)!!.use { it.readBytes() }

    /** The shader files for [era], or nothing when its version cannot use them. */
    fun files(era: PackEra): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        val core = when (era.id) {
            in ORIGINAL -> ORIGINAL_CORE.associateWith { read("/objmc/assets/minecraft/shaders/core/$it") }
            in PORTED -> PORTED_CORE.associateWith { read("/objmc26/assets/minecraft/shaders/core/${era.id}/$it") }
            else -> return emptyMap()
        }
        for ((name, bytes) in core) files["assets/minecraft/shaders/core/$name"] = bytes
        for (name in INCLUDES) {
            // 26.3 reads the vertex index as gl_VertexIndex, so it has its own copy of the main include.
            val path = if (era.id == "26.3" && name == "objmc_main.glsl") "/objmc26/assets/minecraft/shaders/include/26.3/$name" else "/objmc/assets/minecraft/shaders/include/$name"
            files["assets/minecraft/shaders/include/$name"] = read(path)
        }
        return files
    }
}
