package codeloupe.docker

import codeloupe.platform.OwnerOnly
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * A random id of this CodeLoupe installation, `<home>/install-id`. It goes into the labels of the Docker resources the installation
 * makes ([Ownership.INSTALL]). A container inherits the labels of its image, so an image somebody else published with `codeloupe.repo` and
 * `codeloupe.workspace` labels would make every container started from it look like ours; it cannot carry an id it has never seen.
 */
object InstallId {
    private val VALID = Regex("[0-9a-f]{16,64}")

    fun of(home: Path): String {
        val file = home.resolve("install-id")
        read(file)?.let { return it }
        val fresh = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val created = runCatching { OwnerOnly.create(file, fresh + "\n") }.getOrDefault(false)
        return if (created) fresh else read(file) ?: fresh.also { runCatching { OwnerOnly.write(file, it + "\n") } }
    }

    private fun read(file: Path): String? = runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { VALID.matches(it) }
}
