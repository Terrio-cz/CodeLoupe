package codeloupe.write

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

/**
 * Writes a file so that a reader sees the old bytes or the new ones, never a part: the bytes go to a temporary file beside
 * the target and replace it by one rename. A rename that an editor, a virus scanner or the index holds up is tried again.
 * The temporary file is created new (an existing file or link of that name is never written through), [beforeMove] runs just before the
 * rename (the caller re-checks that the target is still what the edit was made from), and the leftovers of a write that was killed -
 * temporary files of this target older than [STALE_MS] - are removed by the next write to it.
 */
internal object AtomicWrite {
    private val BACKOFF_MS = longArrayOf(10, 30, 90, 250, 600)

    const val STALE_MS = 10 * 60_000L

    fun replace(target: Path, bytes: ByteArray, beforeMove: () -> Unit = {}) {
        sweep(target)
        val temp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}$SUFFIX")
        try {
            Files.write(temp, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            copyPermissions(target, temp)
            beforeMove()
            move(temp, target)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** Removes the temporary files an earlier write to [target] left behind when the daemon was killed in the middle of it. */
    internal fun sweep(target: Path, now: Long = System.currentTimeMillis()) {
        val dir = target.parent ?: return
        val prefix = ".${target.fileName}."
        runCatching {
            Files.newDirectoryStream(dir).use { entries ->
                for (entry in entries) {
                    val name = entry.fileName.toString()
                    if (name.startsWith(prefix) && name.endsWith(SUFFIX) && now - Files.getLastModifiedTime(entry).toMillis() > STALE_MS) Files.deleteIfExists(entry)
                }
            }
        }
    }

    private const val SUFFIX = ".codeloupe-tmp"

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
