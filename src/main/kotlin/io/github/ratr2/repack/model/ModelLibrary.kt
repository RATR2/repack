package io.github.ratr2.repack.model

import io.github.ratr2.repack.pack.PackVariant
import java.io.File

class LibraryResult(
    val files: Map<PackVariant, Map<String, ByteArray>>,
    val models: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
)

object ModelLibrary {

    private val INVALID = Regex("[^a-z0-9_-]")

    /** A name as an id: lower case, anything outside a-z, 0-9, `_` and `-` turned into `_`. */
    fun cleanId(name: String): String = name.lowercase().replace(INVALID, "_")

    fun idOf(file: File): String = cleanId(file.nameWithoutExtension)

    /** The ids the files in folder would get, without parsing them. */
    fun ids(folder: File): Set<String> =
        folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().mapTo(HashSet(), ::idOf)

    /** [onProgress] is called with how many files are done and how many there are, after each one. */
    fun build(folder: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): LibraryResult {
        val files = PackVariant.entries.associateWith { HashMap<String, ByteArray>() }
        val models = ArrayList<String>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()

        val sources = folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        for ((index, source) in sources.withIndex()) {
            onProgress(index, sources.size)
            val id = idOf(source)
            if (id in models) {
                errors += "${source.name}: another file already uses the id '$id'."
                continue
            }
            try {
                val model = BbParser.parse(id, source.readText())
                val generated = PackVariant.entries.associateWith { ItemModelGenerator.generate(id, model, it.shelf) }
                val first = generated.getValue(PackVariant.entries.first())
                // The warnings and errors are the same in every version, so they are only listed once.
                first.warnings.mapTo(warnings) { "$id: $it" }
                if (first.errors.isNotEmpty()) {
                    first.errors.mapTo(errors) { "${source.name}: $it" }
                    continue
                }
                for ((variant, result) in generated) files.getValue(variant) += result.files
                models += id
            } catch (e: BbParseException) {
                errors += "${source.name}: ${e.message}"
            } catch (e: RuntimeException) {
                errors += "${source.name}: the file does not have the structure of a .bbmodel (${e.javaClass.simpleName}: ${e.message})."
            } catch (e: StackOverflowError) {
                errors += "${source.name}: the file is nested too deeply to read."
            }
        }
        return LibraryResult(files, models, warnings, errors)
    }
}
