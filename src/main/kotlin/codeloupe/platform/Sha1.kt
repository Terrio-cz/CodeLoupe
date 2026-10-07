package codeloupe.platform

import java.security.MessageDigest

object Sha1 {
    /** Lowercase hex SHA-1 of the UTF-8 bytes of [text]. */
    fun hex(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHexString()
}
