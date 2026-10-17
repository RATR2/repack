package io.github.ratr2.repack.model

import io.github.ratr2.repack.pack.PackVariant
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** What converting one model file gave: its pack files for each version of the pack, and the problems found. */
class Converted(
    val files: Map<PackVariant, Map<String, ByteArray>>,
    val warnings: List<String>,
    val errors: List<String>,
)

class ModelCache(private val dir: File, private val version: String) {

    /** How many models the last builds read from the cache and how many they had to convert. */
    var hits = 0
        private set
    var misses = 0
        private set

    private fun fileFor(id: String, sha1: String) = File(dir, "$id.$sha1.model")

    fun get(id: String, sha1: String): Converted? {
        val file = fileFor(id, sha1)
        if (!file.isFile) return miss()
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readUTF() != MAGIC || input.readUTF() != version || input.readUTF() != sha1) return miss()
                val warnings = List(input.readInt()) { input.readUTF() }
                val errors = List(input.readInt()) { input.readUTF() }
                val files = LinkedHashMap<PackVariant, Map<String, ByteArray>>()
                repeat(input.readInt()) {
                    val variantId = input.readUTF()
                    val variant = PackVariant.entries.first { it.id == variantId }
                    val entries = LinkedHashMap<String, ByteArray>()
                    repeat(input.readInt()) { entries[input.readUTF()] = ByteArray(input.readInt()).also(input::readFully) }
                    files[variant] = entries
                }
                if (PackVariant.entries.any { it !in files }) return miss()
                hits++
                Converted(files, warnings, errors)
            }
        } catch (e: Exception) {
            miss()
        }
    }

    fun put(id: String, sha1: String, converted: Converted) {
        try {
            dir.mkdirs()
            val target = fileFor(id, sha1)
            val temp = File(dir, "${target.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeUTF(MAGIC)
                output.writeUTF(version)
                output.writeUTF(sha1)
                output.writeInt(converted.warnings.size)
                converted.warnings.forEach(output::writeUTF)
                output.writeInt(converted.errors.size)
                converted.errors.forEach(output::writeUTF)
                output.writeInt(converted.files.size)
                for ((variant, entries) in converted.files) {
                    output.writeUTF(variant.id)
                    output.writeInt(entries.size)
                    for ((path, bytes) in entries) {
                        output.writeUTF(path)
                        output.writeInt(bytes.size)
                        output.write(bytes)
                    }
                }
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Not being able to cache only costs time on the next build.
        }
    }

    /** Deletes the cached files of models that changed or are gone: everything not in [live]. */
    fun prune(live: Set<String>) {
        for (file in dir.listFiles().orEmpty()) {
            if (file.name !in live && (file.name.endsWith(".model") || file.name.endsWith(".tmp"))) file.delete()
        }
    }

    /** The file name a model of this id and hash is cached under, for [prune]. */
    fun nameOf(id: String, sha1: String) = fileFor(id, sha1).name

    private fun miss(): Converted? {
        misses++
        return null
    }

    companion object {
        private const val MAGIC = "repack-model-1"

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
