package io.github.ratr2.repack.model

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelCacheTest {

    private val png = Base64.getEncoder().encodeToString(
        Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")
    )

    private fun model(height: Int = 8) = """
        {"resolution": {"width": 16, "height": 16},
         "elements": [{"type": "cube", "name": "box", "uuid": "c1", "from": [0, 0, 0], "to": [16, $height, 16], "origin": [8, 0, 8],
           "faces": {"up": {"uv": [0, 0, 16, 16], "texture": 0}}}],
         "outliner": [],
         "textures": [{"name": "t.png", "uv_width": 16, "uv_height": 16, "source": "data:image/png;base64,$png"}]}
    """.trimIndent()

    private fun models(vararg names: String): File {
        val dir = Files.createTempDirectory("repack-cache-models").toFile()
        names.forEach { File(dir, "$it.bbmodel").writeText(model()) }
        return dir
    }

    private fun cacheDir() = File(Files.createTempDirectory("repack-cache").toFile(), "models")

    @Test
    fun `a second build reads every unchanged model from the cache and gives the same pack`() {
        val folder = models("a", "b")
        val dir = cacheDir()
        val first = ModelLibrary.build(folder, ModelCache(dir, "1"))
        val cache = ModelCache(dir, "1")
        val second = ModelLibrary.build(folder, cache)

        assertEquals(2, cache.hits)
        assertEquals(0, cache.misses)
        assertEquals(first.models, second.models)
        for (variant in first.files.keys) {
            assertEquals(first.files.getValue(variant).keys, second.files.getValue(variant).keys)
            for ((path, bytes) in first.files.getValue(variant)) assertTrue(bytes.contentEquals(second.files.getValue(variant).getValue(path)), path)
        }
    }

    @Test
    fun `only the model that changed is converted again, and its old cache file goes`() {
        val folder = models("a", "b")
        val dir = cacheDir()
        ModelLibrary.build(folder, ModelCache(dir, "1"))
        File(folder, "a.bbmodel").writeText(model(height = 4))

        val cache = ModelCache(dir, "1")
        ModelLibrary.build(folder, cache)
        assertEquals(1, cache.hits)
        assertEquals(1, cache.misses)
        assertEquals(2, dir.listFiles().orEmpty().count { it.name.endsWith(".model") }, "the old file of a is deleted")
    }

    @Test
    fun `a model that is removed loses its cache file`() {
        val folder = models("a", "b")
        val dir = cacheDir()
        ModelLibrary.build(folder, ModelCache(dir, "1"))
        File(folder, "b.bbmodel").delete()
        ModelLibrary.build(folder, ModelCache(dir, "1"))
        assertEquals(listOf("a"), dir.listFiles().orEmpty().map { it.name.substringBefore('.') })
    }

    @Test
    fun `fresh converts everything again, and a new plugin version does not read the old files`() {
        val folder = models("a")
        val dir = cacheDir()
        ModelLibrary.build(folder, ModelCache(dir, "1"))

        val fresh = ModelCache(dir, "1")
        ModelLibrary.build(folder, fresh, fresh = true)
        assertEquals(0, fresh.hits)

        val newer = ModelCache(dir, "2")
        ModelLibrary.build(folder, newer)
        assertEquals(0, newer.hits)
        assertEquals(1, newer.misses)
    }

    @Test
    fun `a damaged cache file is ignored and the model is converted`() {
        val folder = models("a")
        val dir = cacheDir()
        ModelLibrary.build(folder, ModelCache(dir, "1"))
        val file = dir.listFiles().orEmpty().single { it.name.endsWith(".model") }
        file.writeBytes(file.readBytes().copyOf(20))

        val cache = ModelCache(dir, "1")
        val result = ModelLibrary.build(folder, cache)
        assertEquals(listOf("a"), result.models)
        assertEquals(1, cache.misses)
        assertEquals(1, ModelCache(dir, "1").let { ModelLibrary.build(folder, it); it.hits }, "and it is cached properly again")
    }

    @Test
    fun `models with problems are cached with their warnings and errors`() {
        val dir = cacheDir()
        val folder = Files.createTempDirectory("repack-cache-bad").toFile()
        File(folder, "big.bbmodel").writeText(model().replace("[16, 8, 16]", "[16, 64, 16]"))
        val first = ModelLibrary.build(folder, ModelCache(dir, "1"))
        val cache = ModelCache(dir, "1")
        val second = ModelLibrary.build(folder, cache)
        assertEquals(1, cache.hits)
        assertEquals(first.errors, second.errors)
        assertTrue(second.models.isEmpty())
    }
}
