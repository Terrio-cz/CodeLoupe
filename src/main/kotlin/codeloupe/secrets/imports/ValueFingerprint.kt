package codeloupe.secrets.imports

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * What a report may say about a value: whether two occurrences are the same. The digest is keyed with a salt that exists
 * only in this object's memory, so the short hex strings are comparable inside one report and useless for guessing a value
 * offline or matching it against another report.
 */
class ValueFingerprint {
    private val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256")) }

    @Synchronized
    fun of(value: String): String = mac.doFinal(value.toByteArray()).take(HEX_BYTES).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HEX_BYTES = 5

        /** A short stable name for an occurrence, from where it is, not from what it holds. */
        fun id(file: String, locator: String, name: String): String =
            MessageDigest.getInstance("SHA-256").digest("$file|$locator|$name".toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
