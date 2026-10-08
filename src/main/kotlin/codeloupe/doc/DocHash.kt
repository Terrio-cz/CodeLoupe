package codeloupe.doc

import java.security.MessageDigest

/** Short content hashes: enough to tell two versions of a document apart, shown to readers as a handle on a version. */
object DocHash {
    fun of(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
