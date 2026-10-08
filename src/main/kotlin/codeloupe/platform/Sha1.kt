package codeloupe.platform

import java.io.BufferedInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

object Sha1 {
    private const val CR = '\r'.code
    private const val LF = '\n'.code
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** Lowercase hex SHA-1 of [bytes]. */
    fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHexString()

    /** Lowercase hex SHA-1 of the UTF-8 bytes of [text]. */
    fun hex(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).toHexString()

    /**
     * The same for a UTF-8 file, streamed; with [normalized], as if its BOM were gone and CRLF were LF (a checkout with
     * autocrlf of an LF blob). Null when it cannot be read.
     */
    fun ofFile(file: Path, normalized: Boolean = false): String? = try {
        val digest = MessageDigest.getInstance("SHA-1")
        BufferedInputStream(Files.newInputStream(file)).use { input ->
            if (normalized) {
                input.mark(BOM.size)
                if (!input.readNBytes(BOM.size).contentEquals(BOM)) input.reset()
            }
            var pendingCr = false
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (normalized && pendingCr && b != LF) digest.update(CR.toByte())
                pendingCr = normalized && b == CR
                if (!pendingCr) digest.update(b.toByte())
            }
            if (pendingCr) digest.update(CR.toByte())
        }
        digest.digest().toHexString()
    } catch (_: IOException) {
        null
    }
}
