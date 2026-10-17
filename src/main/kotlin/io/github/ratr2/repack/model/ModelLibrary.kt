package io.github.ratr2.repack.model

import io.github.ratr2.repack.pack.PackVariant
import java.io.File

/** The pack files for every model in the models folder, for each version of the pack, and what went wrong along the way. */
class LibraryResult(
    val files: Map<PackVariant, Map<String, ByteArray>>,
    val models: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
)

/** Reads every `.bbmodel` file in a folder and generates its pack files. The file name, cleaned up, is the block id. */
object ModelLibrary {

    private val INVALID = Regex("[^a-z0-9_-]")
    fun cleanId(name: String): String = name.lowercase().replace(INVALID, "_")

    fun idOf(file: File): String = cleanId(file.nameWithoutExtension)
    
    fun ids(folder: File): Set<String> =
        folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().mapTo(HashSet(), ::idOf)

    fun build(
        folder: File,
        cache: ModelCache? = null,
        fresh: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): LibraryResult {
        val files = PackVariant.entries.associateWith { HashMap<String, ByteArray>() }
        val models = ArrayList<String>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()
        val live = HashSet<String>()

        val sources = folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        for ((index, source) in sources.withIndex()) {
            onProgress(index, sources.size)
            val id = idOf(source)
            if (id in models) {
                errors += "${source.name}: another file already uses the id '$id'."
                continue
            }
            try {
                val bytes = source.readBytes()
                val sha1 = ModelCache.sha1(bytes)
                var converted = if (fresh) null else cache?.get(id, sha1)
                if (converted == null) {
                    converted = convert(id, String(bytes, Charsets.UTF_8))
                    cache?.put(id, sha1, converted)
                }
                cache?.let { live += it.nameOf(id, sha1) }
                // The warnings and errors are the same in every version, so they are only listed once.
                converted.warnings.mapTo(warnings) { "$id: $it" }
                if (converted.errors.isNotEmpty()) {
                    converted.errors.mapTo(errors) { "${source.name}: $it" }
                    continue
                }
                for ((variant, result) in converted.files) files.getValue(variant) += result
                models += id
            } catch (e: BbParseException) {
                errors += "${source.name}: ${e.message}"
            } catch (e: RuntimeException) {
                errors += "${source.name}: the file does not have the structure of a .bbmodel (${e.javaClass.simpleName}: ${e.message})."
            } catch (e: StackOverflowError) {
                errors += "${source.name}: the file is nested too deeply to read."
            }
        }
        cache?.prune(live)
        return LibraryResult(files, models, warnings, errors)
    }

    private fun convert(id: String, text: String): Converted {
        val model = BbParser.parse(id, text)
        val generated = PackVariant.entries.associateWith { ItemModelGenerator.generate(id, model, it.shelf) }
        val first = generated.getValue(PackVariant.entries.first())
        return Converted(generated.mapValues { it.value.files }, first.warnings, first.errors)
    }
}
