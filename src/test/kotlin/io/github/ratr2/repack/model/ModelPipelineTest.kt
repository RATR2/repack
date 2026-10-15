package io.github.ratr2.repack.model

import com.google.gson.JsonParser
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelPipelineTest {

    // 1x1 transparent PNG
    private val png = Base64.getEncoder().encodeToString(
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    )

    private fun bbmodel(cube: String, group: String = "") = """
        {
          "meta": {"format_version": "5.0", "model_format": "java_block"},
          "resolution": {"width": 16, "height": 16},
          "elements": [$cube],
          "groups": [$group],
          "outliner": [ {"uuid": "g1", "children": ["c1"]} ],
          "textures": [{"name": "stone.png", "uv_width": 16, "uv_height": 16, "source": "data:image/png;base64,$png"}]
        }
    """.trimIndent()

    private val plainCube = """
        {"type": "cube", "name": "box", "uuid": "c1", "from": [0, 0, 0], "to": [16, 8, 16], "origin": [8, 0, 8],
         "faces": {"north": {"uv": [0, 0, 16, 8], "texture": 0}, "up": {"uv": [0, 0, 16, 16], "texture": 0}, "south": {"uv": [0, 0, 16, 8]}}}
    """.trimIndent()

    @Test
    fun `parses cubes textures and skips faces without a texture`() {
        val model = BbParser.parse("slab", bbmodel(plainCube))
        assertEquals(1, model.cubes.size)
        assertEquals(1, model.textures.size)
        assertEquals(setOf(Direction.NORTH, Direction.UP), model.cubes[0].faces.keys)
        assertTrue(model.warnings.isEmpty(), model.warnings.toString())
    }

    @Test
    fun `generates a model and an item definition`() {
        val generated = ItemModelGenerator.generate("slab", BbParser.parse("slab", bbmodel(plainCube)))
        assertEquals(
            setOf("assets/repack/models/block/slab.json", "assets/repack/items/slab.json", "assets/repack/textures/block/slab/0.png"),
            generated.files.keys,
        )
        val block = JsonParser.parseString(String(generated.files.getValue("assets/repack/models/block/slab.json"))).asJsonObject
        assertEquals("repack:block/slab/0", block.getAsJsonObject("textures").get("0").asString)
        val element = block.getAsJsonArray("elements")[0].asJsonObject
        assertEquals(16.0, element.getAsJsonArray("to")[2].asDouble)
        assertEquals(16.0, element.getAsJsonObject("faces").getAsJsonObject("up").getAsJsonArray("uv")[2].asDouble)
        val item = JsonParser.parseString(String(generated.files.getValue("assets/repack/items/slab.json"))).asJsonObject
        assertEquals("repack:block/slab", item.getAsJsonObject("model").get("model").asString)
    }

    @Test
    fun `keeps a valid single axis rotation and drops an unsupported one with a warning`() {
        fun element(rotation: String) = ItemModelGenerator.generate(
            "r",
            BbParser.parse("r", bbmodel(plainCube.replace("\"origin\"", "\"rotation\": $rotation, \"origin\""))),
        )

        val valid = element("[0, 22.5, 0]")
        val rotation = JsonParser.parseString(String(valid.files.getValue("assets/repack/models/block/r.json"))).asJsonObject
            .getAsJsonArray("elements")[0].asJsonObject.getAsJsonObject("rotation")
        assertEquals("y", rotation.get("axis").asString)
        assertEquals(22.5, rotation.get("angle").asDouble)

        val invalid = element("[-90, 0, 0]")
        assertTrue(invalid.warnings.any { "-90" in it }, invalid.warnings.toString())
    }

    @Test
    fun `warns about cubes under a rotated group`() {
        val group = """{"name": "arm", "uuid": "g1", "rotation": [0, 45, 0], "origin": [8, 8, 8]}"""
        val model = BbParser.parse("g", bbmodel(plainCube, group))
        assertTrue(model.warnings.any { "rotated group" in it }, model.warnings.toString())
    }

    @Test
    fun `carries the display tab into the block model and clamps what vanilla would reject`() {
        val withDisplay = bbmodel(plainCube).replace(
            "\"textures\"",
            """"display": {"gui": {"rotation": [39.29, -40, 6.09], "scale": [0.6, 0.6, 0.6]},
                "head": {"translation": [0, 120, 0], "scale": [8, 8, 8]},
                "fixed": {"rotation": [-90, 0, 0], "translation": [0, 0, -6.75]},
                "on_shelf": {"translation": [0, 0, 4.75]}},
              "textures"""",
        )
        val generated = ItemModelGenerator.generate("t", BbParser.parse("t", withDisplay))
        val display = JsonParser.parseString(String(generated.files.getValue("assets/repack/models/block/t.json"))).asJsonObject.getAsJsonObject("display")
        assertEquals(setOf("gui", "head", "fixed"), display.keySet())
        assertEquals(0.6, display.getAsJsonObject("gui").getAsJsonArray("scale")[0].asDouble)
        assertEquals(80.0, display.getAsJsonObject("head").getAsJsonArray("translation")[1].asDouble)
        assertEquals(4.0, display.getAsJsonObject("head").getAsJsonArray("scale")[0].asDouble)
        assertEquals(-90.0, display.getAsJsonObject("fixed").getAsJsonArray("rotation")[0].asDouble)
        assertTrue(generated.warnings.any { "80 units" in it })
        assertTrue(generated.warnings.any { "4 times" in it })
    }

    @Test
    fun `parses real model files when REPACK_SAMPLES points at a folder`() {
        val folder = System.getenv("REPACK_SAMPLES")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val files = folder.listFiles { f -> f.extension == "bbmodel" }.orEmpty()
        for (file in files) {
            val model = BbParser.parse(file.nameWithoutExtension, file.readText())
            val generated = ItemModelGenerator.generate(file.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9_]"), "_"), model)
            println("${file.name}: ${model.cubes.size} cubes, ${model.textures.size} textures, ${generated.files.size} files, ${generated.warnings.size} warnings")
            generated.warnings.take(3).forEach { println("    $it") }
        }
    }
}
