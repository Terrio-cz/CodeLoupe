package codeloupe.repo

import codeloupe.JsonFormat
import codeloupe.changes.ChangeSet
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.git.Git
import codeloupe.git.GitLayout
import codeloupe.git.GitObjects
import codeloupe.index.Store
import codeloupe.overlay.Overlays
import codeloupe.platform.Sha1
import codeloupe.git.JGitRepos
import codeloupe.query.View
import codeloupe.query.ViewPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

/**
 * Repositories the daemon knows: any path inside a git repository or one of its worktrees maps to one
 * repository (keyed by its git common dir) with one base index of its default branch; each worktree adds an
 * overlay of the files that differ from that base.
 */
class Registry(
    private val config: Config,
    private val queue: JobQueue,
    launcher: BuildLauncher = BuildLauncher(config.buildHeapMb, config.buildTimeoutMs),
    private val log: (String) -> Unit = {},
) {
    private val repos = ConcurrentHashMap<String, RepoState>()
    private val views = ViewPool()
    private val overlays = Overlays(queue, launcher, config.queryTimeoutMs, config.overlayCheckMs, views::release, log)
    private val builds = BaseBuilds(queue, launcher, log, release = views::release, swapped = ::collectOverlays)
    private val mergeBases = MergeBases(queue, launcher, config.queryTimeoutMs)

    /** Read from the `.git` files on every call (well under a millisecond), so a moved or removed worktree is never served from a cache. */
    fun locate(root: String): RepoLocation {
        // The daemon's working directory is its home, so a relative root would name the wrong repository.
        if (!Path.of(root).isAbsolute) throw IllegalArgumentException("root must be an absolute path: $root")
        val path = Path.of(normalize(root))
        if (!path.exists()) throw IllegalArgumentException("root does not exist: $root")
        GitLayout.locate(path)?.let { return RepoLocation(it.worktree, it.commonDir) }
        val out = Git.run(path.toString(), "rev-parse", "--path-format=absolute", "--show-toplevel", "--git-common-dir", allowFail = true)
            ?: throw IllegalArgumentException("not inside a git repository: $root")
        val (worktree, commonDir) = out.trim().lines().map(::normalize)
        return RepoLocation(worktree, commonDir)
    }

    fun repo(commonDir: String): RepoState = repos.computeIfAbsent(commonDir) {
        val id = Sha1.hex(commonDir.lowercase()).take(12)
        val dir = config.home.resolve("repos").resolve(id)
        Files.createDirectories(dir)
        val saved = runCatching { JsonFormat.json.decodeFromString(RepoRecord.serializer(), Files.readString(dir.resolve("repo.json"))) }.getOrNull()
        RepoState(id, dir, commonDir, DefaultRef.of(commonDir)).apply {
            // A base written by another index format (an older extractor) is rebuilt, not served.
            val file = saved?.baseFile?.let { Path.of(it) }
            if (file != null && file.exists() && saved.format == Store.FORMAT) {
                baseCommit = saved.baseCommit
                baseFile = file
            }
            lastBuild = saved?.lastBuild
            // Left behind by a daemon that was killed, and worktrees removed while it was not running.
            builds.clean(this)
            collectOverlays(this)
        }
    }

    /**
     * Base index for the repository's current default-branch commit. A sync the daemon parses itself
     * is waited for; during a larger one the old base keeps answering. Only a missing base makes the caller wait for a
     * full build (bounded by queryTimeoutMs). A commit whose build failed is not retried for [RETRY_FAILED_MS].
     */
    suspend fun base(repo: RepoState): RepoState {
        val head = headOf(repo)
        if (repo.baseCommit == head) return repo
        val failed = synchronized(repo) { repo.failure?.takeIf { it.commit == head && System.currentTimeMillis() - repo.failedAt < RETRY_FAILED_MS } }
        if (failed != null) {
            if (repo.baseFile != null) return repo
            throw IllegalStateException("indexing ${head.take(7)} failed: ${failed.error}")
        }
        if (repo.baseFile == null) {
            val job = builds.full(repo, head)
            withTimeoutOrNull(config.queryTimeoutMs) { job.await() }
                ?: throw BusyException("indexing ${repo.commonDir} (first build); retry in a few seconds")
            return repo
        }
        val sync = withContext(Dispatchers.IO) { builds.sync(repo, head) }
        // A failed sync is recorded in repo.failure; the old base answers meanwhile.
        if (sync.inline) withTimeoutOrNull(config.queryTimeoutMs) { runCatching { sync.job.await() } }
        return repo
    }

    /**
     * Runs [read] on what [root]'s worktree changed against the merge-base with the default branch: the change set, the
     * worktree as it is now and the merge-base versions of the changed files (null when there are none).
     */
    suspend fun <T> changes(root: String, read: (ChangeSet, View, View?) -> T): T {
        val location = locate(root)
        val set = mergeBases.changes(repo(location.commonDir), location.worktree)
        return query(root, speculative = false) { after -> set.beforeFile?.let(::View).use { before -> read(set, after, before) } }
    }

    /**
     * Runs [read] on the base index of [root]'s repository with [root]'s worktree overlay on top. A [speculative] read
     * starts while the worktree is checked and is thrown away when the check changed something: worth it for cheap
     * reads only.
     */
    suspend fun <T> query(root: String, speculative: Boolean = true, read: (View) -> T): T {
        val arrived = System.nanoTime()
        val location = locate(root)
        val repo = base(repo(location.commonDir))
        repeat(ATTEMPTS) {
            val (baseFile, baseCommit) = synchronized(repo) { repo.baseFile!! to repo.baseCommit!! }
            val answer = coroutineScope {
                // Read the overlay as it is while the worktree is checked: the answer stands when the check changed nothing.
                val known = if (speculative) overlays.known(location.worktree, baseCommit) else null
                val early = known?.let { async(Dispatchers.IO) { runCatching { read(repo, baseFile, baseCommit, it.file, read) } } }
                val overlay = overlays.fresh(repo, location.worktree, baseCommit, baseFile, arrived) ?: return@coroutineScope null
                if (overlay == known) {
                    early!!.await().getOrThrow()
                } else {
                    withContext(Dispatchers.IO) { read(repo, baseFile, baseCommit, overlay.file, read) }
                }
            }
            if (answer != null) return answer.value
        }
        throw BusyException("the index of ${location.worktree} keeps changing; retry in a few seconds")
    }

    /** Null when the base or the overlay moved on since [baseCommit]: the caller tries again. */
    private fun <T> read(repo: RepoState, baseFile: Path, baseCommit: String, overlay: Path?, read: (View) -> T): Read<T>? {
        // Taken under the lock that guards the swap, so a new build cannot prune this base in between.
        val lease = synchronized(repo) { if (repo.baseCommit == baseCommit) views.take(baseFile, overlay) else null } ?: return null
        var healthy = false
        try {
            // An overlay refreshed against a newer base in the meantime does not fit this one.
            val answer = if (overlay == null || lease.view.overlayBase() == baseCommit) Read(read(lease.view)) else null
            healthy = true
            return answer
        } finally {
            views.give(lease, healthy)
        }
    }

    /** Closes the open read views and git repositories; the next query opens them again. */
    fun close() {
        views.closeAll()
        JGitRepos.closeIdle()
    }

    fun snapshot(): List<RepoSummary> = repos.values.map { it.summary(overlays.count(it.id)) }

    private fun collectOverlays(repo: RepoState) {
        queue.run(JobQueue.Lane.FAST, "gc:${repo.id}") {
            withContext(Dispatchers.IO) { runCatching { overlays.collect(repo) }.onFailure { log("overlay collection ${repo.id} failed: ${it.message}") } }
        }
    }

    // Read on every query from the ref files (microseconds) or JGit, which re-reads them when their stamps move.
    private fun headOf(repo: RepoState): String = GitObjects.resolve(repo.commonDir, repo.defaultRef)
        ?: throw IllegalStateException("${repo.defaultRef} does not name a commit in ${repo.commonDir}")

    private fun normalize(path: String): String = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')

    /** Wraps a query result, which may itself be null. */
    private class Read<T>(val value: T)

    private companion object {
        const val RETRY_FAILED_MS = 5 * 60_000
        const val ATTEMPTS = 3
    }
}
