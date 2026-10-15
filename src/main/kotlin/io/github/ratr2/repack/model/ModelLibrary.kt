package io.github.ratr2.repack.model

import java.io.File

/** The pack files for every model in the models folder, and what went wrong along the way. */
class LibraryResult(
    val files: Map<String, ByteArray>,
    val models: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
)

/** Reads every `.bbmodel` file in a folder and generates its pack files. The file name, cleaned up, is the block id. */
object ModelLibrary {

    private val INVALID = Regex("[^a-z0-9_-]")

    fun idOf(file: File): String = file.nameWithoutExtension.lowercase().replace(INVALID, "_")

    fun build(folder: File): LibraryResult {
        val files = HashMap<String, ByteArray>()
        val models = ArrayList<String>()
        val warnings = ArrayList<String>()
        val errors = ArrayList<String>()

        val sources = folder.listFiles { f -> f.isFile && f.extension == "bbmodel" }.orEmpty().sortedBy { it.name }
        for (source in sources) {
            val id = idOf(source)
            if (id in models) {
                errors += "${source.name}: another file already uses the id '$id'."
                continue
            }
            try {
                val generated = ItemModelGenerator.generate(id, BbParser.parse(id, source.readText()))
                files += generated.files
                models += id
                generated.warnings.mapTo(warnings) { "$id: $it" }
            } catch (e: BbParseException) {
                errors += "${source.name}: ${e.message}"
            }
        }
        return LibraryResult(files, models, warnings, errors)
    }
}
