package io.github.ratr2.repack.config

import io.github.ratr2.repack.block.BlockTemplate
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMigratorTest {

    private fun resource(path: String) = ConfigMigratorTest::class.java.getResourceAsStream(path)!!.reader().use { it.readText() }

    private fun tempFile(text: String): File = File(Files.createTempDirectory("repack-config").toFile(), "config.yml").apply { writeText(text) }

    private fun backups(file: File) = file.parentFile.listFiles { f -> ".bak-" in f.name }.orEmpty()

    private val shipped = resource("/config.yml")
    private val current = ConfigVersions.of(shipped)
    private val defaults = YamlConfiguration().apply { loadFromString(shipped) }

    @Test
    fun `the version follows the text, not the version lines, spacing or the final newline`() {
        assertEquals(ConfigVersions.of("a: 1\nb: 2\n"), ConfigVersions.of("# DO NOT EDIT. Version: x\nconfig-version: x\n\na: 1  \nb: 2"))
        assertNotEquals(ConfigVersions.of("a: 1\n"), ConfigVersions.of("a: 2\n"))
        assertNotEquals(ConfigVersions.of("# note\na: 1\n"), ConfigVersions.of("# other note\na: 1\n"))
    }

    @Test
    fun `an old file keeps its values and comments, gets the version first and the missing options`() {
        val file = tempFile("# my own note\ndebug: true\nmessages:\n  no-permission: \"Nope.\"\n")
        assertNotNull(ConfigMigrator.migrate(file, current, emptyList(), defaults))

        val text = file.readText()
        assertTrue(text.startsWith("# ${ConfigMigrator.note(current)}\n${ConfigMigrator.KEY}: $current\n"), text)
        assertTrue("# my own note" in text, text)

        val yaml = YamlConfiguration.loadConfiguration(file)
        assertEquals(true, yaml.getBoolean("debug"))
        assertEquals("Nope.", yaml.getString("messages.no-permission"))
        assertEquals(true, yaml.getBoolean("client-notices"))
        assertEquals(40, yaml.getInt("resource-pack.join-delay-ticks"))
        assertTrue(File(file.parentFile, "config.yml.bak-unversioned").isFile)
    }

    @Test
    fun `a fresh copy of the shipped file is only stamped, with no backup`() {
        val file = tempFile(shipped)
        assertNull(ConfigMigrator.migrate(file, current, emptyList(), defaults))
        assertTrue(file.readText().startsWith("# ${ConfigMigrator.note(current)}\n"))
        assertTrue(backups(file).isEmpty())
        assertEquals(YamlConfiguration().apply { loadFromString(shipped) }.getValues(true).filterKeys { it != "config-version" }.keys,
            YamlConfiguration.loadConfiguration(file).getValues(true).filterKeys { it != "config-version" }.keys)
    }

    @Test
    fun `a current file is left exactly as it is`() {
        val file = tempFile("# DO NOT EDIT. Version: $current\nconfig-version: $current\n\n# keep\ndebug:   true\n")
        val before = file.readText()
        assertNull(ConfigMigrator.migrate(file, current, emptyList(), defaults))
        assertEquals(before, file.readText())
    }

    @Test
    fun `migrating twice changes nothing the second time`() {
        val file = tempFile("debug: true\n")
        ConfigMigrator.migrate(file, current, emptyList(), defaults)
        val once = file.readText()
        assertNull(ConfigMigrator.migrate(file, current, emptyList(), defaults))
        assertEquals(once, file.readText())
    }

    @Test
    fun `migrations run on an outdated file and can rename options`() {
        val file = tempFile("config-version: old\nold-name: 5\n")
        val rename = ConfigMigration { if (it.contains("old-name")) { it.set("new-name", it.get("old-name")); it.set("old-name", null) } }
        assertNotNull(ConfigMigrator.migrate(file, "new", listOf(rename)))
        val yaml = YamlConfiguration.loadConfiguration(file)
        assertEquals("new", yaml.getString(ConfigMigrator.KEY))
        assertEquals(5, yaml.getInt("new-name"))
        assertFalse(yaml.contains("old-name"))
        assertTrue(File(file.parentFile, "config.yml.bak-old").isFile)
    }

    @Test
    fun `a version that looks like a path cannot escape through the backup name`() {
        val file = tempFile("config-version: ../../evil\ndebug: true\n")
        ConfigMigrator.migrate(file, current, emptyList(), defaults)
        assertTrue(backups(file).single().name.startsWith("config.yml.bak-"))
        assertTrue(backups(file).single().parentFile == file.parentFile)
        assertTrue(file.parentFile.parentFile.listFiles().orEmpty().none { it.name == "evil" })
    }

    @Test
    fun `a file that is not yaml is reported and left alone`() {
        val file = tempFile("debug: [unclosed\n")
        val before = file.readText()
        assertTrue(ConfigMigrator.migrate(file, current, emptyList(), defaults)!!.contains("not valid YAML"))
        assertEquals(before, file.readText())
    }

    @Test
    fun `block files the plugin writes are already current, whatever the id`() {
        for (id in listOf("table", "chair")) {
            val text = BlockTemplate.render(id)
            assertTrue(text.startsWith("# ${ConfigMigrator.note(BlockTemplate.version)}\n${ConfigMigrator.KEY}: ${BlockTemplate.version}\n"))
            val file = tempFile(text)
            assertNull(ConfigMigrator.migrate(file, BlockTemplate.version))
            assertEquals(text, file.readText())
        }
    }

    @Test
    fun `an older block file is just stamped without a backup`() {
        val file = tempFile(resource("/blocks/table.yml"))
        assertNull(ConfigMigrator.migrate(file, BlockTemplate.version))
        assertEquals(BlockTemplate.version, YamlConfiguration.loadConfiguration(file).getString(ConfigMigrator.KEY))
        assertTrue(backups(file).isEmpty())
    }
}
