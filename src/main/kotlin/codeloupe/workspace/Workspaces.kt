package codeloupe.workspace

import codeloupe.config.Config
import codeloupe.platform.IsoTime
import codeloupe.repo.Registry
import codeloupe.taskcode.TaskPattern
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.TaskRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every workspace of the repositories the daemon is told about (`workspaces.repos`, the repos of the trackers) or
 * has served: git's worktrees plus the stray directories under their worktree roots, with the task's state from the
 * tracker mirror. Nothing is written, deleted or synced; a stray directory is only reported.
 */
class Workspaces(private val config: Config, private val registry: Registry, private val trackers: Trackers) {
    /** All known repositories, or just the one at [repo] (any path inside it). [withSize] sums every directory's files. */
    suspend fun list(repo: String? = null, withSize: Boolean = false): WorkspaceList = withContext(Dispatchers.IO) {
        val problems = ArrayList<String>()
        val locations = LinkedHashMap<String, Pair<String, List<String>>>()
        fun add(path: String, roots: List<String>) {
            runCatching { registry.locate(path) }
                .onSuccess { loc -> locations.merge(key(loc.commonDir), loc.commonDir to roots) { a, b -> a.first to (a.second + b.second) } }
                .onFailure { problems += "$path: ${it.message}" }
        }
        if (repo != null) {
            add(repo, emptyList())
            // The configured roots of that repository apply, whichever of its paths was asked for.
            config.workspaces.repos.forEach { entry -> runCatching { registry.locate(entry.path) }.getOrNull()?.let { if (key(it.commonDir) in locations) add(entry.path, entry.roots) } }
        } else {
            config.workspaces.repos.forEach { add(it.path, it.roots) }
            trackers.mirrors.flatMap { it.instance.repos }.forEach { add(it, emptyList()) }
            registry.snapshot().forEach { add(registry.mainWorktree(it.commonDir).toString(), emptyList()) }
        }
        val repos = locations.values.mapNotNull { (commonDir, roots) ->
            val main = registry.mainWorktree(commonDir)
            val pattern = TaskPattern.of(main, trackers.projects())
            val landed = landed(commonDir, pattern, problems)
            runCatching { WorkspaceScanner(config.workspaces.abandonedDays, ::task, landed).scan(commonDir, main, pattern, roots(main, roots), withSize) }
                .onFailure { problems += "$commonDir: ${it::class.simpleName}: ${it.message.orEmpty().lineSequence().first()}" }.getOrNull()
        }
        WorkspaceList(IsoTime.now(), repos, problems)
    }

    // The configured roots and the `<repo>-worktrees` directory beside the repository, each once.
    private fun roots(main: Path, configured: List<String>): List<String> {
        val beside = main.resolveSibling(main.fileName.toString().lowercase() + "-worktrees").toString()
        return (configured + beside).map { it.replace('\\', '/') }.distinctBy { OrphanDirs.key(Path.of(it)) }.filter { Files.isDirectory(Path.of(it)) }
    }

    // The task history of the default branch, scanned once per repository by the same store `task_code` uses. While a first
    // scan of a long history runs, the answer falls back to the HEAD commit subjects and says so.
    private suspend fun landed(commonDir: String, pattern: TaskPattern, problems: MutableList<String>): (String) -> Boolean {
        val store = try {
            registry.taskCodes.current(registry.repo(commonDir), pattern)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problems += "$commonDir: landed state from commit subjects only (${e.message.orEmpty().lineSequence().first()})"
            null
        }
        return { id -> store?.commitsOf(id)?.isNotEmpty() == true }
    }

    private fun task(id: String) = trackers.mirror(id)?.let { (mirror, canonical) -> TaskRows.row(mirror.store, canonical) }

    private fun key(commonDir: String) = OrphanDirs.key(Path.of(commonDir))
}
