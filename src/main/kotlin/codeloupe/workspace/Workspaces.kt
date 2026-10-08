package codeloupe.workspace

import codeloupe.config.Config
import codeloupe.git.GitLayout
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
import kotlin.io.path.name

/**
 * Every workspace of the repositories the daemon is told about (`workspaces.repos`, the repos of the trackers) or
 * has served: git's worktrees plus the stray directories under their worktree roots, with the task's state from the
 * tracker mirror. Nothing is written, deleted or synced; a stray directory is only reported.
 */
class Workspaces(private val config: Config, private val registry: Registry, private val trackers: Trackers) {
    private val shared = RecentScan(config.workspaces.recentScanMs)

    /**
     * [list] of every known repository without sizes, shared for a short window between the reads that come together
     * (the Workspaces screen asks four routes at once). Only for read-only answers: a decision reads [list].
     */
    suspend fun recent(): WorkspaceList = shared.get { list() }

    /** Forgets the shared scan: a workspace changed (released, cleaned up). */
    fun invalidate() = shared.invalidate()

    /** All known repositories, or just the one at [repo] (any path inside it). [withSize] sums every directory's files. */
    suspend fun list(repo: String? = null, withSize: Boolean = false): WorkspaceList = withContext(Dispatchers.IO) {
        val problems = ArrayList<String>()
        val locations = locations(repo, problems)
        val repos = locations.values.mapNotNull { (commonDir, roots) ->
            val main = registry.mainWorktree(commonDir)
            val pattern = TaskPattern.of(main, trackers.projects())
            val landed = landed(commonDir, pattern, problems)
            runCatching { WorkspaceScanner(config.workspaces.abandonedDays, ::task, landed).scan(commonDir, main, pattern, roots(main, roots), withSize) }
                .onFailure { problems += "$commonDir: ${it::class.simpleName}: ${it.message.orEmpty().lineSequence().first()}" }.getOrNull()
        }
        WorkspaceList(IsoTime.now(), repos, problems)
    }

    // The repositories to read: commonDir -> (commonDir, configured roots). Unreadable ones go to [problems].
    private fun locations(repo: String?, problems: MutableList<String>): LinkedHashMap<String, Pair<String, List<String>>> {
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
        return locations
    }

    /**
     * The workspace [target] names, without scanning any history: a directory of a worktree, or a worktree's name (its
     * directory name, the task id for a task workspace), in the repository at [repo] or in the only known one. A name no
     * worktree has any more (git cleaned it up already) is still a workspace of that repository.
     */
    suspend fun resolve(target: String, repo: String? = null): WorkspaceRef = withContext(Dispatchers.IO) {
        val problems = ArrayList<String>()
        if (target.contains('/') || target.contains('\\')) {
            val path = Path.of(target).toAbsolutePath().normalize()
            val loc = registry.locate(path.toString())
            val main = registry.mainWorktree(loc.commonDir)
            val here = key(path)
            val registration = (GitLayout.registrations(loc.commonDir) ?: emptyList()).filter { here == key(Path.of(it.path)) || here.startsWith(key(Path.of(it.path)) + "/") }.maxByOrNull { it.path.length }
            val dir = registration?.let { Path.of(it.path) } ?: path
            require(key(dir) != key(main)) { "$target is the main worktree of ${main.name}; only a task workspace can be released" }
            return@withContext WorkspaceRef(main.name, dir.name)
        }
        val found = locations(repo, problems).values.map { (commonDir, _) -> commonDir to registry.mainWorktree(commonDir) }
        require(found.isNotEmpty()) { "no known repository" + problems.firstOrNull()?.let { ": $it" }.orEmpty() }
        val named = found.filter { (commonDir, _) -> (GitLayout.registrations(commonDir) ?: emptyList()).any { Path.of(it.path).name.equals(target, ignoreCase = true) } }
        val chosen = when {
            named.size == 1 -> named.single()
            named.size > 1 -> throw IllegalArgumentException("$target exists in ${named.joinToString { it.second.name }}; name the repository with repo")
            found.size == 1 -> found.single()
            else -> throw IllegalArgumentException("$target is not a worktree of ${found.joinToString { it.second.name }}; name the repository with repo")
        }
        val (commonDir, main) = chosen
        val registered = (GitLayout.registrations(commonDir) ?: emptyList()).map { Path.of(it.path) }.firstOrNull { it.name.equals(target, ignoreCase = true) }
        require(registered == null || key(registered) != key(main)) { "$target is the main worktree of ${main.name}; only a task workspace can be released" }
        WorkspaceRef(main.name, registered?.name ?: target)
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

    private fun key(path: Path) = OrphanDirs.key(path)
}
