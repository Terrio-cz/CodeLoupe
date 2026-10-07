package codeloupe.overlay

import codeloupe.daemon.JobQueue
import codeloupe.git.WorktreeGit
import codeloupe.index.InlineParse
import codeloupe.index.Store
import codeloupe.index.StoreUpdater
import codeloupe.platform.NativeCalls
import codeloupe.platform.Sha1
import codeloupe.repo.BuildLauncher
import codeloupe.repo.BusyException
import codeloupe.repo.RepoState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * Worktree overlays: for each worktree, the files that differ from the repository's base, in a store of their own.
 * A query checks its worktree before it reads; there are no watchers, so nothing runs while nobody asks.
 */
class Overlays(
    private val queue: JobQueue,
    private val launcher: BuildLauncher,
    private val waitMs: Long,
    checkMs: Long,
    private val log: (String) -> Unit,
) {
    private val states = ConcurrentHashMap<String, OverlayState>()

    // The walk costs ~30 ms; an agent edits between turns that take seconds, so a check this young is still fresh.
    private val checkNanos = checkMs * 1_000_000

    /**
     * The overlay of [worktree] against [baseCommit], brought up to date by a check that started at most `checkMs`
     * before [arrived] (`System.nanoTime()` when the query came in).
     */
    suspend fun fresh(repo: RepoState, worktree: String, baseCommit: String, baseFile: Path, arrived: Long): OverlayVersion {
        val state = states.computeIfAbsent(key(worktree)) { OverlayState(worktree, repo.id, fileOf(repo, worktree)) }
        state.lock.withLock {
            state.running?.let { if (!settled(it)) throw busy(state) }
            if (state.base != baseCommit || arrived - state.checkedAt > checkNanos) {
                state.checkedAt = System.nanoTime()
                val reconcile = state.base != baseCommit
                val change = withContext(Dispatchers.IO) { plan(repo, state, baseCommit, baseFile) }
                if (reconcile) log("overlay $worktree: checked against ${baseCommit.take(7)} in ${(System.nanoTime() - state.checkedAt) / 1_000_000} ms")
                if (change != null) apply(repo, state, change)
            }
            return state.view!!
        }
    }

    /** The overlay as the last check left it, without checking again; null when it was never checked against [baseCommit]. */
    fun known(worktree: String, baseCommit: String): OverlayVersion? = states[key(worktree)]?.view?.takeIf { it.base == baseCommit }

    /** Overlays of a repository that hold files. */
    fun count(repoId: String): Int = states.values.count { it.repoId == repoId && it.entries.isNotEmpty() }

    /** Deletes the overlays of worktrees that are gone (`git worktree list`, or the directory no longer exists). */
    fun collect(repo: RepoState) {
        val live = WorktreeGit.list(repo.commonDir).filter { Path.of(it).exists() }.mapTo(HashSet(), ::key)
        states.entries.removeIf { (key, state) -> state.repoId == repo.id && key !in live }
        val dir = repo.dir.resolve(DIR)
        if (!dir.exists()) return
        for (file in dir.listDirectoryEntries("*.db")) {
            val worktree = runCatching { Store.open(file, readOnly = true).use { Store.getMeta(it, "worktree") } }.getOrNull()
            if (worktree != null && key(worktree) in live) continue
            // Windows refuses to delete a file a reader still has open: best effort, the next collection retries.
            for (suffix in listOf("", "-wal", "-shm")) runCatching { Files.deleteIfExists(Path.of("$file$suffix")) }
            log("overlay of ${worktree ?: file.fileName} removed: the worktree is gone")
        }
    }

    private fun plan(repo: RepoState, state: OverlayState, baseCommit: String, baseFile: Path): OverlayChange? {
        if (state.base == null) load(state)
        if (state.base == baseCommit) return OverlayPlanner.incremental(state, baseCommit, baseFile)
        val previous = synchronized(repo) { repo.previousFile?.takeIf { state.base != null && repo.previousCommit == state.base } }
        return OverlayPlanner.reconcile(state, baseCommit, baseFile, previous?.takeIf { it.exists() })
    }

    /** An overlay file from an earlier daemon run: its files are reused where their stamps still match. */
    private fun load(state: OverlayState) {
        if (!state.file.exists() || state.fileBase != null) return
        val usable = Store.open(state.file).use { db ->
            if (Store.getMeta(db, "format") != Store.FORMAT) return@use false
            state.fileBase = Store.getMeta(db, "base")
            state.entries = db.createStatement().use { s ->
                s.executeQuery("SELECT path, mtime, size FROM files").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), Stamp(rs.getLong(2), rs.getLong(3))) }
                }
            }
            state.fileBase != null
        }
        if (!usable) deleteFile(state)
    }

    private suspend fun apply(repo: RepoState, state: OverlayState, change: OverlayChange) {
        val update = change.update
        val base = update.meta.getValue("base")
        if (update.size == 0 && (change.entries.isEmpty() || state.fileBase == base)) return commit(state, change, emptyList())
        val heavy = !InlineParse.fits(update.puts)
        val job = queue.run(if (heavy) JobQueue.Lane.HEAVY else JobQueue.Lane.FAST, "overlay:${state.worktree}") {
            withContext(Dispatchers.IO) {
                val result = if (heavy) {
                    launcher.update(repo.commonDir, null, state.file, update, repo.dir)
                } else {
                    Store.open(state.file).use { StoreUpdater.apply(it, update, repo.commonDir) }
                }
                state.fileBase = base
                commit(state, change, result.unread)
                log(
                    "overlay ${state.worktree}: ${update.puts.size} parsed, ${update.copies.size} copied, " +
                        "${update.removes.size + update.tombstones.size} removed in ${result.ms} ms${if (heavy) " (build worker)" else ""}",
                )
                result
            }
        }
        state.running = job
        withTimeoutOrNull(waitMs) { job.await() } ?: throw busy(state)
    }

    private fun commit(state: OverlayState, change: OverlayChange, unread: List<String>) {
        val entries = HashMap(change.entries)
        val scan = HashMap(change.scan)
        // Left unchanged by the update: remembered as before, so the next check reads them again.
        for (path in unread) {
            state.entries[path]?.let { entries[path] = it } ?: entries.remove(path)
            state.scan[path]?.let { scan[path] = it } ?: scan.remove(path)
        }
        state.entries = entries
        state.scan = scan
        state.prune = change.prune
        state.ignored = change.ignored
        val base = change.update.meta.getValue("base")
        state.base = base
        val previous = state.view
        if (previous == null || previous.base != base || change.update.size > 0) {
            state.view = OverlayVersion(state.file.takeIf { entries.isNotEmpty() }, base, (previous?.version ?: 0) + 1)
        }
    }

    /** False while [job] still runs after a query's wait. A failed job counts as settled: the next check plans again. */
    private suspend fun settled(job: Deferred<*>): Boolean = withTimeoutOrNull(waitMs) { runCatching { job.await() } } != null

    private fun busy(state: OverlayState) = BusyException("indexing the changes of ${state.worktree}; retry in a few seconds")

    private fun deleteFile(state: OverlayState) {
        for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("${state.file}$suffix"))
        state.fileBase = null
        state.entries = emptyMap()
    }

    private fun fileOf(repo: RepoState, worktree: String): Path {
        val dir = repo.dir.resolve(DIR)
        Files.createDirectories(dir)
        return dir.resolve("${Sha1.hex(key(worktree)).take(12)}.db")
    }

    // Windows paths compare case-insensitively; git and the caller may spell the drive letter differently.
    private fun key(path: String): String {
        val normal = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')
        return if (NativeCalls.isWindows) normal.lowercase() else normal
    }

    private companion object {
        private const val DIR = "overlays"
    }
}
