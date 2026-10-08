package codeloupe.secrets.imports

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object AtomicFile {
    /** Writes [bytes] to [file] through a temp file beside it, so a crash leaves the old or the new file, never half. */
    fun write(file: Path, bytes: ByteArray) {
        val temp = Files.createTempFile(file.parent, "." + file.fileName, ".tmp")
        try {
            Files.write(temp, bytes)
            runCatching { Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file)) }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
