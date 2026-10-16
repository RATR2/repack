package io.github.ratr2.repack.block

import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Material needs a running server, so the tests use a small fixed set of names. */
private object FakeMaterials : MaterialRules {
    private val blocks = setOf("BARRIER", "GLASS", "STONE", "LIGHT", "SMITHING_TABLE")
    private val items = setOf("PAPER", "DIAMOND", "STICK", "SMITHING_TABLE", "AMETHYST_SHARD")
    override fun exists(name: String) = name in blocks || name in items
    override fun isBlock(name: String) = name in blocks
    override fun isItem(name: String) = name in items
}

class BlockDefinitionsTest {

    private fun parse(yaml: String): BlockDefinition {
        val section = YamlConfiguration().apply { loadFromString(yaml) }
        return BlockDefinitions.parse("table", section, FakeMaterials)
    }

    @Test
    fun `an empty file gives the defaults`() {
        val definition = parse("")
        assertEquals("table", definition.model)
        assertEquals("BARRIER", definition.proxyBlockName)
        assertEquals(ProxyMode.CUSTOM, definition.proxyMode)
        assertEquals(false, definition.vanillaInteraction)
        assertEquals(1.0, definition.breaking.time)
        assertEquals(ToolType.ANY, definition.breaking.tool)
        assertNull(definition.breaking.tier)
        assertEquals(PistonBehavior.BLOCK, definition.piston)
        assertEquals(DisplayContext.NONE, definition.display.context)
        assertEquals(1, definition.drops.size)
        assertIs<SelfDrop>(definition.drops[0])
        assertEquals("PAPER", definition.item.baseName)
    }

    @Test
    fun `uneven recipe rows are padded to a rectangle`() {
        val recipe = parse(
            """
            recipe:
              shape: ["AB", "C"]
              ingredients: {A: STICK, B: DIAMOND, C: PAPER}
            """.trimIndent()
        ).recipe!!
        assertEquals(listOf("AB", "C "), recipe.shape)
    }

    @Test
    fun `numbers that are not real are rejected`() {
        for (yaml in listOf(
            "breaking: {time: .nan}",
            "breaking: {time: .inf}",
            "display: {offset: [0, .nan, 0]}",
            "display: {scale: [1, .inf, 1]}",
            "display: {scale: .nan}",
            "display: {view-range: .inf}",
            "display: {view-range: 1e100}",
            "recipe: {shape: [\"A\"], ingredients: {\" \": STICK, A: STICK}}",
        )) {
            assertFailsWith<DefinitionException>(yaml) { parse(yaml) }
        }
    }

    @Test
    fun `the item can use its own model`() {
        assertNull(parse("").item.model)
        assertEquals("table_item", parse("item: {model: Table_Item}").item.model)
    }

    @Test
    fun `reads every section`() {
        val definition = parse(
            """
            model: Other
            proxy: {block: GLASS, mode: vanilla, vanilla-interaction: true}
            breaking: {time: 2.5, tool: pickaxe, tier: stone, drops-need-tool: true, xp: 3}
            drops:
              - item: DIAMOND
                amount: 1-3
                chance: 0.5
              - block: self
            explosion: {resistant: true}
            piston: destroy
            display: {offset: [0, 0.5, 0], scale: 2.0, view-range: 0.5, brightness: 15, rotate-to-placer: true, context: head}
            item: {base: PAPER, name: "&bTable", lore: ["one", "two"]}
            """.trimIndent()
        )
        assertEquals("other", definition.model)
        assertEquals("GLASS", definition.proxyBlockName)
        assertEquals(ProxyMode.VANILLA, definition.proxyMode)
        assertTrue(definition.vanillaInteraction)
        assertEquals(2.5, definition.breaking.time)
        assertEquals(ToolTier.STONE, definition.breaking.tier)
        assertTrue(definition.breaking.dropsNeedTool)
        assertEquals(3, definition.breaking.xp)
        val diamond = assertIs<ItemDrop>(definition.drops[0])
        assertEquals("DIAMOND", diamond.materialName)
        assertEquals(1..3, diamond.amount.min..diamond.amount.max)
        assertEquals(0.5, diamond.chance)
        assertIs<SelfDrop>(definition.drops[1])
        assertTrue(definition.explosionResistant)
        assertEquals(PistonBehavior.DESTROY, definition.piston)
        assertEquals(0.5, definition.display.offsetY)
        assertEquals(listOf(2f, 2f, 2f), listOf(definition.display.scaleX, definition.display.scaleY, definition.display.scaleZ))
        assertEquals(15, definition.display.brightness)
        assertTrue(definition.display.rotateToPlacer)
        assertEquals(DisplayContext.HEAD, definition.display.context)
        assertEquals(listOf("one", "two"), definition.item.lore)
    }

    @Test
    fun `the generated settings file parses and holds the defaults`() {
        val fromTemplate = parse(BlockTemplate.render("table"))
        val defaults = parse("")
        assertEquals(defaults.model, fromTemplate.model)
        assertEquals(defaults.proxyBlockName, fromTemplate.proxyBlockName)
        assertEquals(defaults.proxyMode, fromTemplate.proxyMode)
        assertEquals(defaults.vanillaInteraction, fromTemplate.vanillaInteraction)
        assertEquals(defaults.breaking.time, fromTemplate.breaking.time)
        assertEquals(defaults.breaking.tool, fromTemplate.breaking.tool)
        assertEquals(defaults.breaking.tier, fromTemplate.breaking.tier)
        assertEquals(defaults.breaking.xp, fromTemplate.breaking.xp)
        assertEquals(defaults.drops.size, fromTemplate.drops.size)
        assertEquals(defaults.explosionResistant, fromTemplate.explosionResistant)
        assertEquals(defaults.piston, fromTemplate.piston)
        assertEquals(defaults.display.context, fromTemplate.display.context)
        assertEquals(defaults.display.brightness, fromTemplate.display.brightness)
        assertEquals(listOf(1f, 1f, 1f), listOf(fromTemplate.display.scaleX, fromTemplate.display.scaleY, fromTemplate.display.scaleZ))
        assertEquals(defaults.item.baseName, fromTemplate.item.baseName)
        assertEquals(defaults.item.lore, fromTemplate.item.lore)
    }

    @Test
    fun `reads a shaped recipe and the item glint`() {
        val definition = parse(
            """
            item: {base: SMITHING_TABLE, glint: true}
            recipe:
              shape: ["aba", "bcb", "aba"]
              ingredients: {a: AMETHYST_SHARD, b: DIAMOND, c: SMITHING_TABLE}
              amount: 2
              group: tables
              category: Building
            """.trimIndent()
        )
        assertTrue(definition.item.glint)
        val recipe = definition.recipe!!
        assertEquals(listOf("aba", "bcb", "aba"), recipe.shape)
        assertEquals(mapOf('a' to "AMETHYST_SHARD", 'b' to "DIAMOND", 'c' to "SMITHING_TABLE"), recipe.ingredients)
        assertEquals(2, recipe.amount)
        assertEquals("tables", recipe.group)
        assertEquals("building", recipe.category)
        assertNull(parse("").recipe)
        assertEquals(false, parse("").item.glint)
    }

    @Test
    fun `bad recipes name the problem`() {
        fun message(yaml: String) = assertFailsWith<DefinitionException> { parse(yaml) }.message.orEmpty()
        assertTrue("recipe.shape" in message("recipe: {shape: [], ingredients: {}}"))
        assertTrue("recipe.shape" in message("recipe: {shape: [abcd], ingredients: {a: STICK}}"))
        assertTrue("recipe.shape uses 'b'" in message("recipe: {shape: [ab], ingredients: {a: STICK}}"))
        assertTrue("recipe.ingredients.a" in message("recipe: {shape: [a], ingredients: {a: NOT_A_THING}}"))
        assertTrue("single character" in message("recipe: {shape: [a], ingredients: {ab: STICK}}"))
        assertTrue("recipe.amount" in message("recipe: {shape: [a], ingredients: {a: STICK}, amount: 99}"))
        assertTrue("recipe.category" in message("recipe: {shape: [a], ingredients: {a: STICK}, category: potions}"))
    }

    @Test
    fun `the bundled table settings file follows the old skript rules`() {
        val text = javaClass.getResource("/blocks/table.yml")!!.readText()
        val table = BlockDefinitions.parse("table", YamlConfiguration().apply { loadFromString(text) }, FakeMaterials)
        assertEquals("table", table.model)
        assertEquals("SMITHING_TABLE", table.proxyBlockName)
        assertEquals(ProxyMode.VANILLA, table.proxyMode)
        assertEquals(false, table.vanillaInteraction)
        assertEquals(listOf(1.01f, 1.08f, 1.01f), listOf(table.display.scaleX, table.display.scaleY, table.display.scaleZ))
        assertEquals(15, table.display.brightness)
        assertEquals("SMITHING_TABLE", table.item.baseName)
        assertTrue(table.item.glint)
        assertEquals("&aInfusion Table", table.item.name)
        assertEquals(listOf("", "&dInfuse or Craft powerful spells with this mythical table.."), table.item.lore)
        assertIs<SelfDrop>(table.drops.single())
        assertEquals(listOf("aba", "bcb", "aba"), table.recipe!!.shape)
        assertEquals("etherealtable", table.recipe!!.group)
    }

    @Test
    fun `scale is one number or one per axis`() {
        val uniform = parse("display: {scale: 1.5}").display
        assertEquals(listOf(1.5f, 1.5f, 1.5f), listOf(uniform.scaleX, uniform.scaleY, uniform.scaleZ))
        val perAxis = parse("display: {scale: [1.01, 1.15, 1.01]}").display
        assertEquals(listOf(1.01f, 1.15f, 1.01f), listOf(perAxis.scaleX, perAxis.scaleY, perAxis.scaleZ))
        val missing = parse("").display
        assertEquals(listOf(1f, 1f, 1f), listOf(missing.scaleX, missing.scaleY, missing.scaleZ))
        assertFailsWith<DefinitionException> { parse("display: {scale: [1, 2]}") }
        assertFailsWith<DefinitionException> { parse("display: {scale: 0}") }
        assertFailsWith<DefinitionException> { parse("display: {scale: [1, -1, 1]}") }
    }

    @Test
    fun `an explicit empty drops list drops nothing`() {
        assertTrue(parse("drops: []").drops.isEmpty())
    }

    @Test
    fun `bad values name the setting`() {
        fun message(yaml: String) = assertFailsWith<DefinitionException> { parse(yaml) }.message.orEmpty()
        assertTrue("breaking.time" in message("breaking: {time: 0}"))
        assertTrue("breaking.tool" in message("breaking: {tool: spoon}"))
        assertTrue("proxy.block" in message("proxy: {block: NOT_A_BLOCK}"))
        assertTrue("proxy.block" in message("proxy: {block: PAPER}"))
        assertTrue("drops[0].chance" in message("drops: [{item: DIAMOND, chance: 2}]"))
        assertTrue("drops[0]" in message("drops: [{amount: 2}]"))
        assertTrue("display.brightness" in message("display: {brightness: 20}"))
        assertTrue("display.offset" in message("display: {offset: [1, 2]}"))
        assertTrue("display.context" in message("display: {context: sideways}"))
    }

    @Test
    fun `amount ranges`() {
        assertEquals(2, AmountRange.parse("2")?.min)
        assertEquals(3, AmountRange.parse("1-3")?.max)
        assertNull(AmountRange.parse("3-1"))
        assertNull(AmountRange.parse("many"))
    }
}
