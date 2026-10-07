package codeloupe.overlay

import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.name

/**
 * A worktree as its last check left it, saved next to its overlay: the first check after a daemon restart (or after
 * the state was evicted) then walks the worktree instead of asking git (3 processes, 150–300 ms on Windows). It
 * stands only beside an overlay file holding exactly [entries] against [base]; otherwise git settles the worktree.
 */
internal data class ScanSnapshot(
    val format: String,
    val base: String,
    val entries: Map<String, Stamp>,
    val scan: Map<String, Stamp>,
    val prune: Set<String>,
    val ignored: Set<String>,
) {
    companion object {
        private const val MAGIC = "codeloupe-scan/1"

        fun fileOf(overlay: Path): Path = overlay.resolveSibling(overlay.name.removeSuffix(".db") + ".scan")

        /** Null when there is no snapshot or it cannot be read. */
        fun read(file: Path): ScanSnapshot? = runCatching {
            DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readUTF() != MAGIC) return null
                ScanSnapshot(input.readUTF(), input.readUTF(), readStamps(input), readStamps(input), readPaths(input), readPaths(input))
            }
        }.getOrNull()

        /** Replaces the snapshot as a whole; when that fails the old one is deleted, never left to describe an older worktree. */
        fun write(file: Path, snapshot: ScanSnapshot) {
            val tmp = file.resolveSibling("${file.name}.tmp")
            try {
                DataOutputStream(Files.newOutputStream(tmp).buffered()).use { out ->
                    out.writeUTF(MAGIC)
                    out.writeUTF(snapshot.format)
                    out.writeUTF(snapshot.base)
                    writeStamps(out, snapshot.entries)
                    writeStamps(out, snapshot.scan)
                    writePaths(out, snapshot.prune)
                    writePaths(out, snapshot.ignored)
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                delete(file)
            }
        }

        fun delete(file: Path) {
            for (path in listOf(file, file.resolveSibling("${file.name}.tmp"))) runCatching { Files.deleteIfExists(path) }
        }

        private fun writeStamps(out: DataOutputStream, stamps: Map<String, Stamp>) {
            out.writeInt(stamps.size)
            for ((path, stamp) in stamps) {
                out.writeUTF(path)
                out.writeLong(stamp.mtime)
                out.writeLong(stamp.size)
            }
        }

        private fun readStamps(input: DataInputStream): Map<String, Stamp> =
            HashMap<String, Stamp>().apply { repeat(input.readInt()) { put(input.readUTF(), Stamp(input.readLong(), input.readLong())) } }

        private fun writePaths(out: DataOutputStream, paths: Set<String>) {
            out.writeInt(paths.size)
            paths.forEach(out::writeUTF)
        }

        private fun readPaths(input: DataInputStream): Set<String> = HashSet<String>().apply { repeat(input.readInt()) { add(input.readUTF()) } }
    }
}
