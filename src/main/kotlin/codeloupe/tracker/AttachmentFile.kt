package codeloupe.tracker

import java.nio.file.Files
import java.nio.file.Path

/** A local file offered as an attachment: it is read only when it is small and not a secret by its name. */
object AttachmentFile {
    const val MAX_BYTES = 4L * 1024 * 1024
    private val SECRET_EXTENSIONS = setOf("pem", "key", "p12", "pfx", "jks", "keystore")

    /** The bytes of [file]; a [TrackerException] says why it is refused. */
    fun read(file: Path): ByteArray {
        val name = file.fileName?.toString().orEmpty()
        if (!Files.isRegularFile(file)) throw TrackerException("no such file: $name")
        if (secretLike(name.lowercase())) throw TrackerException("refused: $name looks like a secret (an .env file, a key or a certificate)")
        val size = Files.size(file)
        if (size > MAX_BYTES) throw TrackerException("refused: $name is ${size / 1024} KB, the limit is ${MAX_BYTES / 1024} KB")
        return Files.readAllBytes(file)
    }

    private fun secretLike(name: String) = name.startsWith(".env") || name.endsWith(".env") || name.substringAfterLast('.', "") in SECRET_EXTENSIONS
}
