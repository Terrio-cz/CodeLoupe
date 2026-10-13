package codeloupe.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The task files of a workspace brain (`<root>/brain/tasks/<id>.md`), read-only. The engine has no command that prints a task file as data, and the
 * file is its append-only notation, so this reads the lines itself, as [BrainFacts] does for a topic. Summaries are kept per file until it changes.
 */
class BrainTaskFiles {
    private class Kept(val modified: Long, val size: Long, val summary: BrainTaskSummary)

    private val kept = ConcurrentHashMap<Path, Kept>()

    /** The notes of task [id], or null when the id is not a task id or the workspace has no file for it. */
    suspend fun read(root: Path, id: String): BrainTaskNotes? = withContext(Dispatchers.IO) {
        if (!VALID.matches(id)) return@withContext null
        val file = tasks(root).resolve("$id.md")
        if (!Files.isRegularFile(file)) return@withContext null
        if (Files.size(file) > MAX_BYTES) throw BrainException(BrainException.Kind.FAILED, "$id.md is larger than ${MAX_BYTES / 1024 / 1024} MB")
        BrainTaskFile.parse(id, Files.readString(file))
    }

    /** The summary of every task file of the workspace, newest last entry first. A file that cannot be read is left out. */
    suspend fun summaries(root: Path): List<BrainTaskSummary> = withContext(Dispatchers.IO) {
        val dir = tasks(root)
        if (!Files.isDirectory(dir)) return@withContext emptyList()
        Files.newDirectoryStream(dir, "*.md").use { stream -> stream.toList() }
            .filter { VALID.matches(it.fileName.toString().removeSuffix(".md")) }
            .mapNotNull { summary(it) }
            .sortedWith(compareByDescending<BrainTaskSummary> { it.lastAt.orEmpty() }.thenBy { it.id })
    }

    private fun summary(file: Path): BrainTaskSummary? = runCatching {
        val size = Files.size(file)
        val modified = Files.getLastModifiedTime(file).toMillis()
        kept[file]?.takeIf { it.modified == modified && it.size == size }?.let { return it.summary }
        if (size > MAX_BYTES) return null
        val summary = BrainTaskFile.parse(file.fileName.toString().removeSuffix(".md"), Files.readString(file)).summary()
        kept[file] = Kept(modified, size, summary)
        summary
    }.getOrNull()

    private fun tasks(root: Path): Path = root.resolve("brain").resolve("tasks")

    companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        private val VALID = Regex("[A-Za-z][A-Za-z0-9]*-\\d{1,9}")
    }
}
