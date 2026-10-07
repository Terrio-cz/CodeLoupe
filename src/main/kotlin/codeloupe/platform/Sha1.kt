package codeloupe.platform

import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest

object Sha1 {
    /** Lowercase hex SHA-1 of the UTF-8 bytes of [text]. */
    fun hex(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHexString()

    /** The same for a UTF-8 file, read as a stream; null when it cannot be read. */
    fun ofFile(file: Path): String? = try {
        val digest = MessageDigest.getInstance("SHA-1")
        DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        digest.digest().toHexString()
    } catch (_: IOException) {
        null
    }
}
