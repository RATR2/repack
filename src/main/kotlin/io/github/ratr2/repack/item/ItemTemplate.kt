package io.github.ratr2.repack.item

import io.github.ratr2.repack.config.ConfigMigrator
import io.github.ratr2.repack.config.ConfigVersions

object ItemTemplate {

    val version: String = ConfigVersions.of(body("ID"))

    fun render(id: String): String = "# ${ConfigMigrator.note(version)}\n${ConfigMigrator.KEY}: $version\n\n" + body(id)

    private fun body(id: String): String = """
        # Settings for the item "$id". Give it with /repack give $id or /give @s repack:$id.

        base: PAPER                     # the vanilla item it is made from
        # model: chair                  # a .bbmodel in models/ the item shows (a model used only by items does not become a block)
        # name: "&aGreen <#ff8800>orange"   # & codes and <#rrggbb> colors; empty: made from the id
        lore: []                        # one line each, with the same colors
        glint: false                    # looks enchanted
        # stack: ...                    # an exact item saved in the builder; it decides everything above except the model
        override-model: true            # with a saved stack: true puts `model` on it, false keeps the model it has
        # components: "[minecraft:custom_data={a:1}]"   # raw components for developers, as in /give
    """.trimIndent() + "\n"
}
