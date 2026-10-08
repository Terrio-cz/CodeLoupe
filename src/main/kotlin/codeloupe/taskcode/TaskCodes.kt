package codeloupe.taskcode

import codeloupe.daemon.JobQueue
import codeloupe.git.GitObjects
import codeloupe.lang.Languages
import codeloupe.repo.BusyException
import codeloupe.repo.RepoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The task records of every repository the daemon knows: one store each, scanned up to the default branch's tip when
 * asked for. The first scan of a long history runs in the heavy lane and a caller waits [waitMs] for it, like a
 * first base build; later scans see a few commits and finish inline.
 */
class TaskCodes(private val queue: JobQueue, private val waitMs: Long, private val log: (String) -> Unit = {}) : AutoCloseable {
    private val stores = ConcurrentHashMap<String, Pair<String, TaskCodeStore>>()

    /** The store of [repo], current to the default branch's tip, for task ids of [pattern]. */
    suspend fun current(repo: RepoState, pattern: TaskPattern): TaskCodeStore {
        val store = open(repo, pattern)
        val tip = GitObjects.resolve(repo.commonDir, repo.defaultRef) ?: throw IllegalStateException("${repo.defaultRef} does not name a commit in ${repo.commonDir}")
        if (store.scannedTip(repo.defaultRef) == tip) return store
        val first = store.scannedTip(repo.defaultRef) == null
        val job = queue.run(if (first) JobQueue.Lane.HEAVY else JobQueue.Lane.FAST, "task-scan:${repo.id}") {
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val result = HistoryScan(store, pattern).scan(repo.commonDir, repo.defaultRef, tip)
                log("task scan ${repo.id}: ${result.walked} commits walked, ${result.recorded} recorded in ${System.currentTimeMillis() - started} ms" + if (result.truncated) " (history cut at ${HistoryScan.MAX_COMMITS})" else "")
                result
            }
        }
        withTimeoutOrNull(waitMs) { job.await() } ?: throw BusyException("scanning the history of ${repo.defaultRef} for task ids; retry in a few seconds")
        return store
    }

    /**
     * The declarations [commit] changed in [paths] (every source file of the commit when null), parsed once and kept.
     * Null when that means parsing more source files than the daemon does inline.
     */
    fun decls(repo: RepoState, store: TaskCodeStore, commit: TaskCommit, paths: Collection<String>? = null): List<CommitDecl>? {
        val files = store.filesOf(commit.sha).filter { Languages.languageOf(it.path) != null && (paths == null || it.path in paths) }
        val missing = files.filter { it.path !in store.declsDone(commit.sha) }
        if (missing.isNotEmpty()) {
            val decls = BlobDecls.of(missing, GitObjects.blobs(repo.commonDir)) ?: return null
            store.putDecls(commit.sha, missing.map { it.path }, decls)
        }
        return if (paths == null) store.declsOf(commit.sha) else paths.flatMap { store.declsOf(commit.sha, it) }
    }

    private fun open(repo: RepoState, pattern: TaskPattern): TaskCodeStore = synchronized(stores) {
        val key = pattern.source + " " + pattern.regex.pattern
        stores[repo.id]?.let { (opened, store) -> if (opened == key) return store else store.close() }
        TaskCodeStore(repo.dir.resolve("tasks.db"), key).also { stores[repo.id] = key to it }
    }

    override fun close() = synchronized(stores) {
        stores.values.forEach { it.second.close() }
        stores.clear()
    }
}
