package codeloupe.write

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Writes a file so that a reader sees the old bytes or the new ones, never a part: the bytes go to a temporary file beside
 * the target and replace it by one rename. A rename that an editor, a virus scanner or the index holds up is tried again.
 */
internal object AtomicWrite {
    private val BACKOFF_MS = longArrayOf(10, 30, 90, 250, 600)

    fun replace(target: Path, bytes: ByteArray) {
        val temp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID().toString().take(8)}.codeloupe-tmp")
        try {
            Files.write(temp, bytes)
            copyPermissions(target, temp)
            move(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun move(temp: Path, target: Path) {
        var attempt = 0
        while (true) {
            try {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
                return
            } catch (e: IOException) {
                val transient = e is AccessDeniedException || e.javaClass == IOException::class.java
                if (!transient || attempt >= BACKOFF_MS.size) throw WriteRefused("could not replace $target: ${e.message}")
                Thread.sleep(BACKOFF_MS[attempt++])
            }
        }
    }

    private fun copyPermissions(from: Path, to: Path) {
        runCatching {
            val permissions: Set<PosixFilePermission> = Files.getPosixFilePermissions(from)
            Files.setPosixFilePermissions(to, permissions)
        }
    }
}
