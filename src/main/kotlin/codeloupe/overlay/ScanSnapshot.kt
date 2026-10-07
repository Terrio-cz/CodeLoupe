package codeloupe.overlay

import codeloupe.git.GitLayout
import codeloupe.git.GlobalExcludes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * A worktree as its last check left it, saved next to its overlay: the first check after a daemon restart (or after
 * the state was evicted) then walks the worktree instead of asking git (3 processes, 150–300 ms on Windows). It
 * stands only beside an overlay file holding exactly [entries] against [base], and while HEAD, the index and the
 * exclude rules ([gitState]) are as they were; otherwise git settles the worktree. Moved `.gitignore` files
 * ([ignoreFiles]) are caught by the first check's walk.
 */
internal data class ScanSnapshot(
    val format: String,
    val base: String,
    val gitState: Map<String, Stamp>,
    val ignoreFiles: Map<String, Stamp>,
    val entries: Map<String, Stamp>,
    val scan: Map<String, Stamp>,
    val prune: Set<String>,
    val ignored: Set<String>,
) {
    companion object {
        private const val MAGIC = "codeloupe-scan/3"
        private const val INDEX = "index"
        private const val INDEX_ENTRIES = "index entries"
        private const val GLOBAL_EXCLUDES = "global excludes "

        fun fileOf(overlay: Path): Path = overlay.resolveSibling(overlay.name.removeSuffix(".db") + ".scan")

        /**
         * Stamps of what can change which files git ignores or tracks without touching a source file or a `.gitignore`:
         * HEAD, the index, the number of files the index tracks (`git add -f`, `git rm --cached`), `info/exclude` and
         * the global excludes file. Empty when the layout is not readable, which no snapshot matches.
         */
        fun gitState(worktree: String): Map<String, Stamp> {
            val root = Path.of(worktree)
            val gitDir = runCatching { GitLayout.gitDir(root) }.getOrNull() ?: return emptyMap()
            val common = GitLayout.commonDir(gitDir)
            val globalExcludes = GlobalExcludes.file(common)
            return mapOf(
                "HEAD" to stampOf(gitDir.resolve("HEAD")),
                INDEX to stampOf(gitDir.resolve("index")),
                INDEX_ENTRIES to indexEntries(gitDir.resolve("index")),
                "exclude" to stampOf(common.resolve("info/exclude")),
                GLOBAL_EXCLUDES + globalExcludes to stampOf(globalExcludes),
            )
        }

        /**
         * True when HEAD, the exclude rules or the number of tracked files moved between two [gitState]s. The index
         * stamp is left out: IDEs refresh the index all the time, and only a restart (a snapshot) has to distrust what
         * happened to it unseen. Its entry count moves only when a file starts or stops being tracked.
         */
        fun rulesMoved(old: Map<String, Stamp>, new: Map<String, Stamp>): Boolean = old - INDEX != new - INDEX

        /** Null when there is no snapshot or it cannot be read. */
        fun read(file: Path): ScanSnapshot? = runCatching {
            DataInputStream(Files.newInputStream(file).buffered()).use { input ->
                if (input.readUTF() != MAGIC) return null
                ScanSnapshot(input.readUTF(), input.readUTF(), readStamps(input), readStamps(input), readStamps(input), readStamps(input), readPaths(input), readPaths(input))
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
                    writeStamps(out, snapshot.gitState)
                    writeStamps(out, snapshot.ignoreFiles)
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

        /** The entry count in the 12-byte index header (`DIRC`, version, count); [Stamp.MISSING] when it cannot be read. */
        private fun indexEntries(index: Path): Stamp = try {
            RandomAccessFile(index.toFile(), "r").use { file ->
                val header = ByteArray(12)
                file.readFully(header)
                if (String(header, 0, 4, Charsets.US_ASCII) == "DIRC") Stamp(ByteBuffer.wrap(header, 8, 4).int.toLong() and 0xFFFFFFFFL, 0) else Stamp.MISSING
            }
        } catch (_: IOException) {
            Stamp.MISSING
        }

        private fun stampOf(file: Path): Stamp = try {
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
            Stamp(attrs.lastModifiedTime().to(TimeUnit.MICROSECONDS), attrs.size())
        } catch (_: IOException) {
            Stamp.MISSING
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
