package codeloupe.overlay

import codeloupe.daemon.JobQueue
import codeloupe.events.EventTypes
import codeloupe.git.GitLayout
import codeloupe.git.GitObjects
import codeloupe.git.WorktreeGit
import codeloupe.index.InlineParse
import codeloupe.index.Store
import codeloupe.index.StoreUpdater
import codeloupe.platform.NativeCalls
import codeloupe.platform.Sha1
import codeloupe.platform.TimedPart
import codeloupe.platform.Timings
import codeloupe.repo.BuildLauncher
import codeloupe.repo.BusyException
import codeloupe.repo.RepoState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Worktree overlays: for each worktree, the files that differ from the repository's base, in a store of their own.
 * A query checks its worktree before it reads; there are no watchers, so nothing runs while nobody asks.
 */
class Overlays(
    private val queue: JobQueue,
    private val launcher: BuildLauncher,
    private val waitMs: Long,
    checkMs: Long,
    /** Called before an overlay file is deleted, so nothing keeps it open. */
    private val release: (Path) -> Unit,
    private val log: (String) -> Unit,
    private val emit: (String, JsonObject) -> Unit = { _, _ -> },
) {
    private val states = ConcurrentHashMap<String, OverlayState>()

    // The walk costs ~30 ms; an agent edits between turns that take seconds, so a check this young is still fresh.
    private val checkNanos = checkMs * 1_000_000

    /**
     * The overlay of [worktree] against [baseCommit], brought up to date by a check that started at most `checkMs`
     * before [arrived] (`System.nanoTime()` when the query came in). Null when the base moved on: the caller starts over.
     */
    suspend fun fresh(repo: RepoState, worktree: String, baseCommit: String, baseFile: Path, arrived: Long): OverlayVersion? {
        val deadline = System.nanoTime() + waitMs * 1_000_000
        while (true) {
            val state = stateOf(repo, worktree)
            val pending = state.lock.withLock {
                // Evicted while we waited for the lock: a fresh state owns the overlay now.
                if (states[key(worktree)] !== state) return@withLock null
                // The base moved since the caller read it: checking against the old one would only flip the overlay back.
                if (synchronized(repo) { repo.baseCommit } != baseCommit) return null
                state.running?.takeIf { it.isActive }?.let { return@withLock it }
                if (state.base == baseCommit && !state.mustCheck && arrived - state.checkedAt <= checkNanos) return state.view!!
                state.checkedAt = System.nanoTime()
                state.mustCheck = false
                val reconcile = state.base != baseCommit
                val change = withContext(Dispatchers.IO) { Timings.measure(TimedPart.CHECK) { plan(repo, state, baseCommit, baseFile) } }
                if (reconcile) log("overlay $worktree: checked against ${baseCommit.take(7)} in ${(System.nanoTime() - state.checkedAt) / 1_000_000} ms")
                change?.let { start(repo, state, it) } ?: return state.view!!
            } ?: continue
            // Waited for outside the lock: every query of the worktree waits for the same job, none queues behind another.
            val outcome = withTimeoutOrNull(maxOf(1, (deadline - System.nanoTime()) / 1_000_000)) { runCatching { pending.await() } }
                ?: throw busy(state)
            outcome.getOrThrow()
        }
    }

    /** The overlay as the last check left it, without checking again; null when it was never checked against [baseCommit]. */
    fun known(worktree: String, baseCommit: String): OverlayVersion? = states[key(worktree)]?.view?.takeIf { it.base == baseCommit }

    /** Overlays of a repository that hold files. */
    fun count(repoId: String): Int = states.values.count { it.repoId == repoId && it.entries.isNotEmpty() }

    /**
     * Deletes the overlays of worktrees that are gone (`git worktree list`, or the directory no longer exists). Files
     * are matched by name, never opened: one a refresh is still writing belongs to a live worktree.
     */
    fun collect(repo: RepoState) {
        val worktrees = GitLayout.worktrees(repo.commonDir) ?: WorktreeGit.list(repo.commonDir)
        val live = worktrees.filter { Path.of(it).exists() }.mapTo(HashSet(), ::key)
        states.entries.removeIf { (key, state) -> state.repoId == repo.id && key !in live }
        val dir = repo.dir.resolve(DIR)
        if (!dir.exists()) return
        val keep = live.mapTo(HashSet(), ::nameOf)
        val gone = dir.listDirectoryEntries().filter { it.name.substringBefore('.') !in keep }
        // Windows refuses to delete a file a reader still has open: best effort, the next collection retries.
        for (file in gone) {
            release(file)
            runCatching { Files.deleteIfExists(file) }
        }
        if (gone.isNotEmpty()) log("overlays of removed worktrees deleted: ${gone.map { it.name.substringBefore('.') }.distinct().joinToString()}")
    }

    // A state per worktree that was queried; the least recently used go first beyond MAX_STATES. An evicted overlay
    // keeps its file and is checked against git on its next query.
    private fun stateOf(repo: RepoState, worktree: String): OverlayState {
        val key = key(worktree)
        states[key]?.let { return it.also { it.usedAt = System.nanoTime() } }
        val state = states.computeIfAbsent(key) { OverlayState(worktree, repo.id, fileOf(repo, worktree)) }
        if (states.size > MAX_STATES) evictIdle()
        return state
    }

    private fun evictIdle() {
        for ((key, state) in states.entries.sortedBy { it.value.usedAt }.take(states.size - MAX_STATES)) {
            if (state.running?.isActive == true || !state.lock.tryLock()) continue
            states.remove(key, state)
            state.lock.unlock()
        }
    }

    private fun plan(repo: RepoState, state: OverlayState, baseCommit: String, baseFile: Path): OverlayChange? {
        val gitState = ScanSnapshot.gitState(state.worktree)
        if (state.base == null) {
            load(state)
            restore(state, gitState)
        }
        // A checkout or new exclude rules change what git ignores without touching a source file: git settles that.
        if (state.base == baseCommit && !ScanSnapshot.rulesMoved(state.gitState, gitState)) {
            // The index changed (an IDE refreshes it all the time) while this daemon watched: a restart need not distrust it.
            if (gitState != state.gitState) {
                state.gitState = gitState
                saveSnapshot(state)
            }
            return OverlayPlanner.incremental(state, baseCommit, baseFile)
        }
        val previous = synchronized(repo) { repo.previousFile?.takeIf { state.base != null && repo.previousCommit == state.base } }
        return OverlayPlanner.reconcile(state, baseCommit, baseFile, previous?.takeIf { it.exists() }).copy(gitState = gitState)
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

    /** The worktree as the last check of an earlier daemon run saw it, when its snapshot matches the overlay file. */
    private fun restore(state: OverlayState, gitState: Map<String, Stamp>) {
        val snapshot = ScanSnapshot.read(ScanSnapshot.fileOf(state.file)) ?: return
        if (snapshot.format != Store.FORMAT || snapshot.entries != state.entries) return
        if (gitState.isEmpty() || snapshot.gitState != gitState) return
        if (state.entries.isNotEmpty() && snapshot.base != state.fileBase) return
        state.gitState = gitState
        state.ignoreFiles = snapshot.ignoreFiles
        state.base = snapshot.base
        state.scan = snapshot.scan
        state.prune = snapshot.prune
        state.ignored = snapshot.ignored
        state.view = OverlayVersion(state.file.takeIf { state.entries.isNotEmpty() }, snapshot.base, 1)
    }

    /** The refresh job that writes [change], or null when nothing needs writing and [change] is already committed. */
    private fun start(repo: RepoState, state: OverlayState, change: OverlayChange): Deferred<*>? {
        val update = change.update
        val base = update.meta.getValue("base")
        if (update.size == 0 && (change.entries.isEmpty() || state.fileBase == base)) {
            commit(state, change, emptyList())
            return null
        }
        val heavy = !InlineParse.fits(update.puts)
        val job = queue.run(if (heavy) JobQueue.Lane.HEAVY else JobQueue.Lane.FAST, "overlay:${state.worktree}") {
            withContext(Dispatchers.IO) {
                val started = System.nanoTime()
                val result = try {
                    if (heavy) {
                        launcher.update(repo.commonDir, null, state.file, update, repo.dir)
                    } else {
                        Store.open(state.file).use { StoreUpdater.apply(it, update, GitObjects.blobs(repo.commonDir)) }
                    }
                } catch (e: Exception) {
                    state.mustCheck = true
                    log("overlay ${state.worktree}: refresh failed: ${e.message}")
                    throw e
                }
                state.fileBase = base
                commit(state, change, result.unread)
                Timings.add(TimedPart.REFRESH, System.nanoTime() - started)
                log(
                    "overlay ${state.worktree}: ${update.puts.size} parsed, ${update.copies.size} copied, " +
                        "${update.removes.size + update.tombstones.size} removed in ${result.ms} ms${if (heavy) " (build worker)" else ""}",
                )
                emit(
                    EventTypes.OVERLAY_REFRESHED,
                    buildJsonObject {
                        put("repo", repo.id)
                        put("worktree", state.worktree)
                        put("parsed", update.puts.size)
                        put("copied", update.copies.size)
                        put("removed", update.removes.size + update.tombstones.size)
                        put("ms", result.ms)
                    },
                )
                result
            }
        }
        state.running = job
        return job
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
        state.ignoreFiles = change.ignoreFiles
        change.gitState?.let { state.gitState = it }
        val base = change.update.meta.getValue("base")
        state.base = base
        val previous = state.view
        if (previous == null || previous.base != base || change.update.size > 0) {
            state.view = OverlayVersion(state.file.takeIf { entries.isNotEmpty() }, base, (previous?.version ?: 0) + 1)
        }
        // Written after the overlay file: a snapshot never runs ahead of the entries the file holds.
        saveSnapshot(state)
    }

    private fun saveSnapshot(state: OverlayState) {
        val snapshot = ScanSnapshot(Store.FORMAT, state.base!!, state.gitState, state.ignoreFiles, state.entries, state.scan, state.prune, state.ignored)
        ScanSnapshot.write(ScanSnapshot.fileOf(state.file), snapshot)
    }

    private fun busy(state: OverlayState) = BusyException("indexing the changes of ${state.worktree}; retry in a few seconds")

    private fun deleteFile(state: OverlayState) {
        release(state.file)
        ScanSnapshot.delete(ScanSnapshot.fileOf(state.file))
        for (suffix in listOf("", "-wal", "-shm")) Files.deleteIfExists(Path.of("${state.file}$suffix"))
        state.fileBase = null
        state.entries = emptyMap()
    }

    private fun fileOf(repo: RepoState, worktree: String): Path {
        val dir = repo.dir.resolve(DIR)
        Files.createDirectories(dir)
        return dir.resolve("${nameOf(key(worktree))}.db")
    }

    private fun nameOf(key: String) = Sha1.hex(key).take(12)

    // Windows paths compare case-insensitively; git and the caller may spell the drive letter differently.
    private fun key(path: String): String {
        val normal = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/')
        return if (NativeCalls.isWindows) normal.lowercase() else normal
    }

    private companion object {
        const val DIR = "overlays"

        /** Worktrees whose last walk stays in memory (~150 B per file each). */
        const val MAX_STATES = 16
    }
}
