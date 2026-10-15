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
    private val blocks = setOf("BARRIER", "GLASS", "STONE", "LIGHT")
    private val items = setOf("PAPER", "DIAMOND", "STICK")
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
        assertEquals(1, definition.drops.size)
        assertIs<SelfDrop>(definition.drops[0])
        assertEquals("PAPER", definition.item.baseName)
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
            display: {offset: [0, 0.5, 0], scale: 2.0, view-range: 0.5, brightness: 15, rotate-to-placer: true}
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
        assertEquals(2f, definition.display.scale)
        assertEquals(15, definition.display.brightness)
        assertTrue(definition.display.rotateToPlacer)
        assertEquals(listOf("one", "two"), definition.item.lore)
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
    }

    @Test
    fun `amount ranges`() {
        assertEquals(2, AmountRange.parse("2")?.min)
        assertEquals(3, AmountRange.parse("1-3")?.max)
        assertNull(AmountRange.parse("3-1"))
        assertNull(AmountRange.parse("many"))
    }
}
