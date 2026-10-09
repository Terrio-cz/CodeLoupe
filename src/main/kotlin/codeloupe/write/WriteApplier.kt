package codeloupe.write

import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path

/**
 * Puts the files of a write on disk, all or none: each path is checked against the policy, the files are locked in path order and
 * compared with what the edit was made from (one that changed since is refused), then written atomically one by one. A write
 * that fails half way puts back the files already written. What was done goes to the journal.
 */
internal class WriteApplier(private val policy: WritePolicy, private val journal: WriteJournal, private val locks: FileLocks = FileLocks()) {
    fun apply(op: String, root: String, worktree: Path, mainWorktree: Path, changes: List<FileChange>, note: String): List<WrittenFile> {
        for (change in changes) {
            for (path in listOf(change.path, change.target).distinct()) {
                policy.refusal(worktree, mainWorktree, path, change.text.takeIf { path == change.target })?.let { throw WriteRefused(it) }
            }
        }
        val paths = changes.flatMap { listOf(it.path, it.target) }.distinct().map { worktree.resolve(it).toAbsolutePath().normalize().toString() }
        return locks.withAll(paths) {
            for (change in changes) check(worktree, change)
            val done = ArrayList<FileChange>()
            val written = changes.flatMap { written(it) }
            try {
                for (change in changes) {
                    write(worktree, change)
                    done += change
                }
                // A write that cannot be logged is not kept: the journal is how a write is found and undone by hand.
                journal.append(op, root, note, written)
            } catch (e: Exception) {
                val stuck = done.asReversed().mapNotNull { restore(worktree, it) }
                if (stuck.isNotEmpty()) throw WriteRefused("write failed (${e.message}) and could not be fully undone: ${stuck.joinToString()}; check git status")
                throw if (e is WriteRefused) e else WriteRefused("write failed and was undone: ${e.message}")
            }
            written
        }
    }

    // What the edit was made from is what is on disk.
    private fun check(worktree: Path, change: FileChange) {
        val current = SourceText.read(worktree.resolve(change.path))
        when {
            change.original == null && current != null -> throw WriteRefused("${change.path} exists already; nothing was written")
            change.original != null && current?.sha != change.original.sha -> throw WriteRefused("${change.path} changed while the edit was prepared; read it again")
        }
        if (change.movedTo != null && Files.exists(worktree.resolve(change.movedTo))) throw WriteRefused("${change.movedTo} exists already; nothing was written")
    }

    private fun write(worktree: Path, change: FileChange) {
        val target = worktree.resolve(change.target)
        Files.createDirectories(target.parent)
        // The edit was checked against the disk under the lock; checked again just before the rename, the window in which another program can win is a few milliseconds.
        AtomicWrite.replace(target, change.text.toByteArray(Charsets.UTF_8)) { check(worktree, change) }
        if (change.movedTo != null) {
            try {
                Files.delete(worktree.resolve(change.path))
            } catch (e: Exception) {
                // The new file is written but the old one stays: a half-moved file is not in `done`, so it is put back here.
                restore(worktree, change)
                throw e
            }
        }
    }

    // The path that could not be put back, or null.
    private fun restore(worktree: Path, change: FileChange): String? = try {
        if (change.movedTo != null) Files.deleteIfExists(worktree.resolve(change.movedTo))
        if (change.original != null) AtomicWrite.replace(worktree.resolve(change.path), change.original.bytes) else Files.deleteIfExists(worktree.resolve(change.path))
        null
    } catch (e: Exception) {
        change.path
    }

    private fun written(change: FileChange): List<WrittenFile> {
        val after = Sha1.hex(change.text.toByteArray(Charsets.UTF_8))
        val before = change.original?.sha
        return if (change.movedTo == null) listOf(WrittenFile(change.path, before, after)) else listOf(WrittenFile(change.path, before, null), WrittenFile(change.movedTo, null, after))
    }
}
