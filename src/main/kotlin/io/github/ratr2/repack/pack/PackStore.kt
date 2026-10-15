package io.github.ratr2.repack.pack

import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.security.MessageDigest

/** A pack zip on disk, named by the SHA-1 of its bytes. */
data class PackEntry(val sha1: String, val size: Int)

/**
 * Keeps built packs under `packs/<sha1>.zip` and remembers which one is built (latest output of /repack build) and which is
 * published (the one players are sent). Building never changes what players get; only [publish] does.
 */
class PackStore(private val dataFolder: File) {

    private val dir = File(dataFolder, "packs").apply { mkdirs() }
    private val stateFile = File(dataFolder, "state.yml")

    var built: PackEntry? = null
        private set
    var published: PackEntry? = null
        private set

    init {
        val state = YamlConfiguration.loadConfiguration(stateFile)
        built = entryOf(state.getString("built"))
        published = entryOf(state.getString("published"))
    }

    fun add(zip: ByteArray): PackEntry {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$sha1.zip")
        if (!file.exists()) file.writeBytes(zip)
        return PackEntry(sha1, zip.size).also {
            built = it
            save()
        }
    }

    /** Makes the built pack the one players get. Returns null when nothing has been built yet. */
    fun publish(): PackEntry? {
        val entry = built ?: return null
        published = entry
        save()
        return entry
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
        state.set("built", built?.sha1)
        state.set("published", published?.sha1)
        state.save(stateFile)
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{40}")
    }
}
