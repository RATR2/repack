package io.github.ratr2.repack.dev

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The tokens of upload links. A token is 32 random bytes in URL-safe base64 and belongs to one player and one kind of file. Only its SHA-256 is kept, so
 * nothing in memory can be turned back into a link, and a token is looked up by that hash. They end when the player's dev mode does.
 */
class UploadTokens {

    class Grant(val player: UUID, val kind: UploadKind)

    private val grants = ConcurrentHashMap<String, Grant>()
    private val owned = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    /** A new token for the player and kind; the one they had for that kind stops working. */
    fun create(player: UUID, kind: UploadKind): String {
        owned.remove("$player/${kind.id}")?.let(grants::remove)
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val key = hash(token)
        grants[key] = Grant(player, kind)
        owned["$player/${kind.id}"] = key
        return token
    }

    fun lookup(token: String?): Grant? = if (token.isNullOrEmpty() || token.length > MAX_TOKEN) null else grants[hash(token)]

    /** Ends every token of a player. */
    fun revoke(player: UUID) {
        for (kind in UploadKind.entries) owned.remove("$player/${kind.id}")?.let(grants::remove)
    }

    val count: Int get() = grants.size

    private fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_TOKEN = 128
    }
}
