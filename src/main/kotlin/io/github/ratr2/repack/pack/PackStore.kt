package io.github.ratr2.repack.pack

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class PackEntry(val sha1: String, val size: Int)

/**
 * Keeps built packs under `packs/<sha1>.zip` and remembers, for each client era, which one is built (the latest output of
 * /repack build) and which is published (the one players are sent). Building never changes what players get; only publish does.
 */
class PackStore(private val dataFolder: File) {

    private val dir = File(dataFolder, "packs").apply { mkdirs() }
    private val stateFile = File(dataFolder, "state.yml")

    private val builtEntries = LinkedHashMap<PackEra, PackEntry>()
    private val publishedEntries = LinkedHashMap<PackEra, PackEntry>()

    val built: Map<PackEra, PackEntry> get() = builtEntries
    val published: Map<PackEra, PackEntry> get() = publishedEntries
    val hasPublished: Boolean get() = publishedEntries.isNotEmpty()

    init {
        val state = YamlConfiguration.loadConfiguration(stateFile)
        for (era in PackEra.entries) {
            entryOf(state.getString("built.${era.id}"))?.let { builtEntries[era] = it }
            entryOf(state.getString("published.${era.id}"))?.let { publishedEntries[era] = it }
        }
        // Packs left over from before the server stopped are cleaned up at once, not at the next build.
        prune()
    }

    fun add(variant: PackEra, zip: ByteArray): PackEntry {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$sha1.zip")
        // A file of the wrong size is what a crash or a full disk leaves behind, and it would fail the client's hash check.
        if (!file.exists() || file.length() != zip.size.toLong()) writeAtomically(file, zip)
        return PackEntry(sha1, zip.size).also {
            builtEntries[variant] = it
            save()
            prune()
        }
    }

    fun publish(): Map<PackEra, PackEntry> {
        if (builtEntries.isEmpty()) return emptyMap()
        publishedEntries.putAll(builtEntries)
        save()
        prune()
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
        writeAtomically(stateFile, state.saveToString().toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Deletes packs that neither state refers to, once they are an hour old: a client may still be downloading the pack that was
     * published a moment ago.
     */
    private fun prune() {
        val keep = (builtEntries.values + publishedEntries.values).mapTo(HashSet()) { "${it.sha1}.zip" }
        val cutoff = System.currentTimeMillis() - PRUNE_AFTER_MILLIS
        for (file in dir.listFiles().orEmpty()) {
            if (file.name in keep || !file.isFile) continue
            if ((file.name.endsWith(".zip") || file.name.endsWith(".tmp")) && file.lastModified() < cutoff) file.delete()
        }
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{40}")
        const val PRUNE_AFTER_MILLIS = 60L * 60 * 1000
    }
}
