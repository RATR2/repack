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
    fun cleanId(name: String): String = name.lowercase().replace(INVALID, "_")

    fun idOf(file: File): String = cleanId(file.nameWithoutExtension)
    
    fun ids(folder: File): Set<String> =
        folder.listFiles { f -> f.isFile && f.extension.equals("bbmodel", ignoreCase = true) }.orEmpty().mapTo(HashSet(), ::idOf)

    fun build(
        folder: File,
        cache: ModelCache? = null,
        fresh: Boolean = false,
        mergeSize: Int = 0,
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
            if (id.isEmpty()) {
                errors += "${source.name}: the file has no name before .bbmodel, so it has no id."
                continue
            }
            if (id in models) {
                errors += "${source.name}: another file already uses the id '$id'."
                continue
            }
            try {
                val bytes = source.readBytes()
                val sha1 = ModelCache.sha1(bytes) + if (mergeSize in 2..ItemModelGenerator.MAX_MERGE) "-m$mergeSize" else ""
                var converted = if (fresh) null else cache?.get(id, sha1)
                if (converted == null) {
                    converted = convert(id, String(bytes, Charsets.UTF_8), mergeSize)
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
            } catch (e: java.io.IOException) {
                errors += "${source.name}: the file could not be read (${e.message})."
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

    private fun convert(id: String, text: String, mergeSize: Int): Converted {
        val model = BbParser.parse(id, text)
        val generated = PackVariant.entries.associateWith { ItemModelGenerator.generate(id, model, it.shelf, mergeSize) }
        // What each version of the pack loses is reported once, in the order it appears.
        val warnings = generated.values.flatMap { it.warnings }.distinct()
        val errors = generated.values.flatMap { it.errors }.distinct()
        return Converted(generated.mapValues { it.value.files }, warnings, errors)
    }
}
