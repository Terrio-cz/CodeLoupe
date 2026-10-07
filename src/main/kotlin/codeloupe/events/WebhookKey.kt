package codeloupe.events

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The key every webhook body is signed with: `<home>/webhook.key`, made on first use and readable only by its owner.
 * A receiver on this machine reads the file; the key never travels in an event, a response or a log.
 */
class WebhookKey(val file: Path) {
    private val key: ByteArray by lazy { load() }

    /** `sha256=<hex>` of HMAC-SHA256 over `<timestamp>.<body>`, so a captured request cannot be replayed later as new. */
    fun sign(timestamp: Long, body: String): String {
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8)))
    }

    @Synchronized
    private fun load(): ByteArray {
        if (Files.exists(file)) return HexFormat.of().parseHex(Files.readString(file).trim())
        Files.createDirectories(file.parent)
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val tmp = Files.createTempFile(file.parent, "webhook-", ".key")
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(tmp, HexFormat.of().formatHex(bytes))
        Files.move(tmp, file)
        return bytes
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
