package codeloupe.hooks

import java.nio.file.Files
import java.nio.file.Path

/**
 * [SourceFiles] for replaying old calls, where the repositories may be gone: a source file counts as indexed, and has as many
 * lines as the transcript's own result of the call shows ([current]), else as the file has today. A directory counts when it, or
 * the nearest of its parents that still exists, is inside a git repository or holds one (the worktrees of a finished task are
 * removed, their siblings are not) and a source file lies under it. It answers what a daemon that had indexed every
 * repository in the transcripts would say, and no more: a directory that belongs to no repository is not source.
 */
class ReplaySources : SourceFiles {
    /** The call being replayed. */
    var current: ReplayCall? = null

    private val directories = HashMap<String, Boolean>()

    override fun file(path: String): SourceFile? {
        if (!SourceNames.isSource(path)) return null
        val lines = current?.lines ?: onDisk(path) ?: return null
        return SourceFile(path, path.substringAfterLast('/'), lines)
    }

    override fun hasSources(dir: String): Boolean = directories.getOrPut(dir) {
        runCatching {
            var existing: Path? = Path.of(dir)
            while (existing != null && !Files.isDirectory(existing)) existing = existing.parent
            existing != null && existing.nameCount > MIN_DEPTH && nearRepository(existing) && Files.walk(existing, DEPTH).use { walk -> walk.anyMatch { SourceNames.isSource(it.toString()) } }
        }.getOrDefault(false)
    }

    private fun nearRepository(dir: Path): Boolean =
        generateSequence(dir) { it.parent }.any { Files.exists(it.resolve(".git")) } || Files.newDirectoryStream(dir).use { children -> children.any { Files.exists(it.resolve(".git")) } }

    private fun onDisk(path: String): Int? = runCatching {
        val bytes = Files.readAllBytes(Path.of(path))
        val newlines = bytes.count { it == NEWLINE }
        if (bytes.isEmpty() || bytes.last() == NEWLINE) newlines else newlines + 1
    }.getOrNull()

    private companion object {
        const val NEWLINE: Byte = 10
        const val DEPTH = 8

        // Too close to the root of a drive is not a repository.
        const val MIN_DEPTH = 2
    }
}
