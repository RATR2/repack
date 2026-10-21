package io.github.ratr2.repack.entity

import io.github.ratr2.repack.config.ConfigMigrator
import io.github.ratr2.repack.config.ConfigVersions

/** The settings file the plugin writes for a new entity: every option, set to its default. */
object EntityTemplate {

    val version: String = ConfigVersions.of(body("ID", "ID"))

    fun render(id: String, model: String): String = "# ${ConfigMigrator.note(version)}\n${ConfigMigrator.KEY}: $version\n\n" + body(id, model)

    private fun body(id: String, model: String): String = """
        # Settings for the model entity "$id". /repack entity summon $id puts one in the world, and it stays there, saved with the chunk.

        model: $model                   # a .bbmodel in models/
        # animation: idle               # a model with meshes plays this Blockbench animation (its name, lowercase); leave out for the still model
        scale: 1                        # one number for all three, or x, y, z
        offset: [0, 0, 0]               # blocks, from the entity's position to the middle of the model
        view-range: 1.0                 # 1.0 is 64 blocks
        # brightness: 15                # 0 to 15, leave out to light it like the world around it
        context: none                   # which Display tab setting it uses: none, gui, ground, fixed, head, ...
    """.trimIndent() + "\n"
}
