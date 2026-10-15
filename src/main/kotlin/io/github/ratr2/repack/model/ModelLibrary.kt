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

    fun idOf(file: File): String = file.nameWithoutExtension.lowercase().replace(INVALID, "_")

    /** The ids the files in folder would get, without parsing them. */
    fun ids(folder: File): Set<String> =
        folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().mapTo(HashSet(), ::idOf)

    fun build(folder: File): LibraryResult {
        val files = PackVariant.entries.associateWith { HashMap<String, ByteArray>() }
        val models = ArrayList<String>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()

        val sources = folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().sortedBy { it.name }
        for (source in sources) {
            val id = idOf(source)
            if (id in models) {
                errors += "${source.name}: another file already uses the id '$id'."
                continue
            }
            try {
                val model = BbParser.parse(id, source.readText())
                for ((variant, variantFiles) in files) {
                    val generated = ItemModelGenerator.generate(id, model, variant.shelf)
                    variantFiles += generated.files
                    // The warnings are the same in every version, so they are only listed once.
                    if (variant == PackVariant.entries.first()) generated.warnings.mapTo(warnings) { "$id: $it" }
                }
                models += id
            } catch (e: BbParseException) {
                errors += "${source.name}: ${e.message}"
            }
        }
        return LibraryResult(files, models, warnings, errors)
    }
}
