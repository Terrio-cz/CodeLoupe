package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.platform.OwnerOnly
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The secret of `<home>/daemon.token` (owner-only, created with its mode set): whoever can read the file may act through the daemon.
 * The file is the header line a client sends, `x-codeloupe-token: <value>`, so `curl -H @daemon.token` presents it without the secret
 * on a command line; a bare value is read too.
 * It is created once and kept across restarts, so running clients stay valid; a changed or deleted file is noticed on the next
 * request, which is how it is rotated (write a new value, no restart). The value never goes into a log or `/status`.
 */
class DaemonToken private constructor(private val file: Path, private var value: String) {
    private var stamp = modified()

    /** The token now: the file's, when somebody replaced it with a valid one; a file that is gone or unusable is written again. */
    @Synchronized
    fun current(): String {
        val seen = modified()
        if (seen != stamp) {
            stamp = seen
            val fromFile = parse(runCatching { Files.readString(file) }.getOrNull())
            if (fromFile != null) value = fromFile else restore()
        }
        return value
    }

    /** Whether [presented] is the token (compared in constant time). */
    fun matches(presented: String?): Boolean =
        presented != null && MessageDigest.isEqual(presented.toByteArray(Charsets.UTF_8), current().toByteArray(Charsets.UTF_8))

    /** The answer to a client's [nonce]: shows the daemon holds the token without sending it, so a client can check who it talks to first. */
    fun proof(nonce: String): String? = nonce.takeIf { NONCE.matches(it) }?.let { proof(current(), it) }

    private fun restore() {
        runCatching { OwnerOnly.write(file, line(value)) }
        stamp = modified()
    }

    private fun modified(): Long = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(-1L)

    companion object {
        const val FILE = "daemon.token"
        private val NONCE = Regex("[A-Za-z0-9]{8,64}")
        private val TOKEN = Regex("[A-Za-z0-9_-]{32,128}")

        fun valid(text: String): Boolean = TOKEN.matches(text)

        private fun line(value: String) = "${CodeLoupe.TOKEN_HEADER}: $value\n"

        private fun parse(text: String?): String? = text?.trim()?.substringAfterLast(' ')?.takeIf(::valid)

        /** The home's token; made on first use. Two daemons starting together end up with the one that won the creation. */
        fun open(home: Path): DaemonToken {
            val file = home.resolve(FILE)
            read(home)?.let { return DaemonToken(file, it) }
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }.toHexString()
            val created = runCatching { OwnerOnly.create(file, line(fresh)) }.getOrDefault(false)
            if (!created) read(home)?.let { return DaemonToken(file, it) }
            if (!created) OwnerOnly.write(file, line(fresh))
            return DaemonToken(file, fresh)
        }

        /** The token in [home], for a client; null when there is none yet or the file is unusable. */
        fun read(home: Path): String? = parse(runCatching { Files.readString(home.resolve(FILE)) }.getOrNull())

        /** SHA-256 over a fixed label, the token and the nonce; the same text in `hook.sh` (`sha256sum`) and the app (`node:crypto`). */
        fun proof(token: String, nonce: String): String =
            MessageDigest.getInstance("SHA-256").digest("codeloupe-proof:$token:$nonce".toByteArray(Charsets.UTF_8)).toHexString()
    }
}
