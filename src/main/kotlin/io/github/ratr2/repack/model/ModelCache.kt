package io.github.ratr2.repack.model

import io.github.ratr2.repack.pack.PackVariant
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class Converted(
    val files: Map<PackVariant, Map<String, ByteArray>>,
    val warnings: List<String>,
    val errors: List<String>,
)

class ModelCache(private val dir: File, private val version: String) {

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
                val warnings = List(count(input)) { input.readUTF() }
                val errors = List(count(input)) { input.readUTF() }
                val files = LinkedHashMap<PackVariant, Map<String, ByteArray>>()
                repeat(count(input)) {
                    val variantId = input.readUTF()
                    val variant = PackVariant.entries.first { it.id == variantId }
                    val entries = LinkedHashMap<String, ByteArray>()
                    repeat(count(input)) {
                        val path = input.readUTF()
                        val size = input.readInt()
                        // A damaged file must not make an array of whatever size it says.
                        if (size < 0 || size > file.length()) throw java.io.IOException("a size larger than the file")
                        entries[path] = ByteArray(size).also(input::readFully)
                    }
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

    /** A count from the file, which is damaged when it is negative or more than any model has. */
    private fun count(input: DataInputStream): Int {
        val n = input.readInt()
        if (n < 0 || n > MAX_COUNT) throw java.io.IOException("a count of $n")
        return n
    }

    /** writeUTF takes 65535 bytes at most; a longer message is cut (20000 characters are at most 60000 bytes). */
    private fun fit(text: String) = if (text.length > MAX_TEXT_CHARS) text.take(MAX_TEXT_CHARS) + "..." else text

    fun put(id: String, sha1: String, converted: Converted) {
        val target = fileFor(id, sha1)
        val temp = File(dir, "${target.name}.tmp")
        try {
            dir.mkdirs()
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeUTF(MAGIC)
                output.writeUTF(version)
                output.writeUTF(sha1)
                output.writeInt(converted.warnings.size)
                converted.warnings.forEach { output.writeUTF(fit(it)) }
                output.writeInt(converted.errors.size)
                converted.errors.forEach { output.writeUTF(fit(it)) }
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
            // Not being able to cache only costs time on the next build; a half written file is not left.
            runCatching { temp.delete() }
        }
    }

    fun prune(live: Set<String>) {
        for (file in dir.listFiles().orEmpty()) {
            if (file.name !in live && (file.name.endsWith(".model") || file.name.endsWith(".tmp"))) file.delete()
        }
    }

    fun nameOf(id: String, sha1: String) = fileFor(id, sha1).name

    private fun miss(): Converted? {
        misses++
        return null
    }

    companion object {
        private const val MAGIC = "repack-model-1"
        private const val MAX_COUNT = 100_000
        private const val MAX_TEXT_CHARS = 20_000

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }

}
