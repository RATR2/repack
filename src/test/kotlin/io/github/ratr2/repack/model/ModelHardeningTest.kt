package io.github.ratr2.repack.model

import io.github.ratr2.repack.block.BlockDefinitions
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelHardeningTest {

    private val png = Base64.getEncoder().encodeToString(
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    )

    private fun model(from: String = "[0, 0, 0]", to: String = "[16, 8, 16]", uv: Int = 16, outliner: String = "[]") = """
        {"resolution": {"width": 16, "height": 16},
         "elements": [{"type": "cube", "name": "box", "uuid": "c1", "from": $from, "to": $to, "origin": [8, 0, 8],
           "faces": {"up": {"uv": [0, 0, 16, 16], "texture": 0}}}],
         "outliner": $outliner,
         "textures": [{"name": "t.png", "uv_width": $uv, "uv_height": $uv, "source": "data:image/png;base64,$png"}]}
    """.trimIndent()

    private fun folderWith(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("repack-models").toFile()
        for ((name, text) in files) File(dir, name).writeText(text)
        return dir
    }

    @Test
    fun `a file with the wrong structure is an error, not a crash, and the other models still build`() {
        val folder = folderWith(
            "bad.bbmodel" to """{"elements": ["not an object"], "textures": []}""",
            "worse.bbmodel" to """{"elements": [{"type": "cube", "from": ["x", 0, 0], "to": [1, 1, 1]}], "textures": []}""",
            "good.bbmodel" to model(),
        )
        val result = ModelLibrary.build(folder)
        assertEquals(listOf("good"), result.models)
        assertEquals(2, result.errors.size, result.errors.toString())
    }

    @Test
    fun `a cube outside the range vanilla loads is an error and the model is left out`() {
        val folder = folderWith("big.bbmodel" to model(to = "[16, 64, 16]"), "ok.bbmodel" to model())
        val result = ModelLibrary.build(folder)
        assertEquals(listOf("ok"), result.models)
        assertTrue(result.errors.single().contains("-16 to 32"), result.errors.toString())
        assertTrue(result.files.values.none { files -> files.keys.any { "big" in it } })
    }

    @Test
    fun `a texture with a zero UV size is rejected`() {
        assertFailsWith<BbParseException> { BbParser.parse("m", model(uv = 0)) }
    }

    @Test
    fun `an absurdly deep outliner is an error instead of a stack overflow`() {
        val deep = "{\"uuid\": \"g\", \"children\": [".repeat(5000) + "]}".repeat(5000)
        val folder = folderWith("deep.bbmodel" to model(outliner = "[$deep]"))
        val result = ModelLibrary.build(folder)
        assertTrue(result.models.isEmpty())
        assertEquals(1, result.errors.size, result.errors.toString())
    }

    @Test
    fun `ids are cleaned the same way for files and for model settings`() {
        assertEquals("my_chair", ModelLibrary.cleanId("My Chair"))
        assertEquals("table_v2", ModelLibrary.cleanId("table.v2"))
        assertEquals("a-b_c", ModelLibrary.cleanId("a-b_c"))
    }

    @Test
    fun `block files with names that cannot be ids are reported and skipped`() {
        val dir = Files.createTempDirectory("repack-blocks").toFile()
        File(dir, "my chair.yml").writeText("model: x\n")
        File(dir, "table.v2.yml").writeText("model: x\n")
        val loaded = BlockDefinitions.load(dir, emptySet())
        assertTrue(loaded.definitions.isEmpty())
        assertEquals(2, loaded.errors.size, loaded.errors.toString())
    }
}
