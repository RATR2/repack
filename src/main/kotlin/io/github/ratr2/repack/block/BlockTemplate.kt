package io.github.ratr2.repack.block

/** The settings file the plugin writes for a block the first time it sees its model: every option, set to its default. */
object BlockTemplate {

    fun render(id: String): String = """
        # Settings for the block "$id". Every option is here with its default, so change what you need.
        # _example.yml explains each one. To make a second block from the same model, copy this file
        # (table2.yml next to table.yml) and keep `model` pointing at the same .bbmodel file.

        model: $id

        proxy:
          block: BARRIER                # the hidden real block under the model, what players click and collide with
          mode: custom                  # custom: breaking.time applies. vanilla: the proxy block's own mining speed
          vanilla-interaction: false    # true keeps what the proxy block does when right-clicked

        breaking:
          time: 1.0                     # seconds to break
          tool: any                     # any, pickaxe, axe, shovel, hoe, sword or shears
          # tier: stone                 # weakest tool that gets drops: wooden, stone, iron, diamond or netherite
          drops-need-tool: false        # true: breaking with the wrong tool drops nothing
          xp: 0

        drops:
          - block: self                 # the block's own item
          # - item: DIAMOND
          #   amount: 1-2
          #   chance: 0.25

        explosion:
          resistant: false              # true: explosions leave it standing
          drops: false                  # when it is destroyed by an explosion

        piston: block                   # block (will not move) or destroy

        display:
          offset: [0, 0, 0]             # blocks, from the centre of the block
          scale: [1, 1, 1]              # x, y, z. One number scales all three
          view-range: 1.0               # 1.0 is 64 blocks
          # brightness: 15              # 0 to 15, leave out to light it like the world around it
          rotate-to-placer: false       # face the player who places it, in 90 degree steps
          context: none                 # which Display tab setting the placed block uses: none, gui, ground, fixed, head, ...

        item:
          base: PAPER                   # what the item that places this block is made from
          # name: "&bTable"
          lore: []
    """.trimIndent() + "\n"
}
