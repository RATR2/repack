package io.github.ratr2.repack.pack

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.security.MessageDigest

/** A pack zip on disk, named by the SHA-1 of its bytes. */
data class PackEntry(val sha1: String, val size: Int)

/**
 * Keeps built packs under `packs/<sha1>.zip` and remembers, for each PackVariant, which one is built (the latest output of
 * /repack build) and which is published (the one players are sent). Building never changes what players get; only publish does.
 */
class PackStore(private val dataFolder: File) {

    private val dir = File(dataFolder, "packs").apply { mkdirs() }
    private val stateFile = File(dataFolder, "state.yml")

    private val builtEntries = LinkedHashMap<PackVariant, PackEntry>()
    private val publishedEntries = LinkedHashMap<PackVariant, PackEntry>()

    val built: Map<PackVariant, PackEntry> get() = builtEntries
    val published: Map<PackVariant, PackEntry> get() = publishedEntries
    val hasPublished: Boolean get() = publishedEntries.isNotEmpty()

    init {
        val state = YamlConfiguration.loadConfiguration(stateFile)
        for (variant in PackVariant.entries) {
            entryOf(state.getString("built.${variant.id}"))?.let { builtEntries[variant] = it }
            entryOf(state.getString("published.${variant.id}"))?.let { publishedEntries[variant] = it }
        }
    }

    fun add(variant: PackVariant, zip: ByteArray): PackEntry {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$sha1.zip")
        if (!file.exists()) file.writeBytes(zip)
        return PackEntry(sha1, zip.size).also {
            builtEntries[variant] = it
            save()
        }
    }

    /** Makes the built packs the ones players get. Returns what is now published, empty when nothing has been built yet. */
    fun publish(): Map<PackVariant, PackEntry> {
        if (builtEntries.isEmpty()) return emptyMap()
        publishedEntries.putAll(builtEntries)
        save()
        return published
    }

    /** The zip for a hash, or null if it isn't a hash we know. Rejects anything that isn't 40 hex characters. */
    fun file(sha1: String): File? {
        if (!HASH.matches(sha1)) return null
        return File(dir, "$sha1.zip").takeIf { it.isFile }
    }

    private fun entryOf(sha1: String?): PackEntry? =
        sha1?.let { file(it) }?.let { PackEntry(it.nameWithoutExtension, it.length().toInt()) }

    private fun save() {
        val state = YamlConfiguration()
        for ((variant, entry) in builtEntries) state.set("built.${variant.id}", entry.sha1)
        for ((variant, entry) in publishedEntries) state.set("published.${variant.id}", entry.sha1)
        state.save(stateFile)
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{40}")
    }
}
