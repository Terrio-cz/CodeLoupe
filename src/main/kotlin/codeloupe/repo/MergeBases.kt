package codeloupe.repo

import codeloupe.changes.ChangeSet
import codeloupe.changes.ChangedFile
import codeloupe.daemon.JobQueue
import codeloupe.git.DiffEntry
import codeloupe.git.Git
import codeloupe.git.WorktreeGit
import codeloupe.index.FilePut
import codeloupe.index.InlineParse
import codeloupe.index.Store
import codeloupe.index.StoreUpdate
import codeloupe.index.StoreUpdater
import codeloupe.lang.Languages
import codeloupe.platform.IsoTime
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * What a worktree changed against the merge-base with the default branch, and an index of the merge-base versions of
 * those files. That index belongs to the merge-base commit, whose blobs never change: it only grows by the files a
 * later call asks for, parsed in the daemon when few, in a build worker in the heavy lane when many.
 */
internal class MergeBases(private val queue: JobQueue, private val launcher: BuildLauncher, private val waitMs: Long) {
    suspend fun changes(repo: RepoState, worktree: String): ChangeSet = withContext(Dispatchers.IO) {
        val mergeBase = mergeBase(repo, worktree)
        val diff = DiffEntry.parse(Git.run(worktree, "diff", "--raw", "-z", "--no-renames", "--no-abbrev", mergeBase, "--")!!)
        val untracked = WorktreeGit.untracked(worktree).toSet()
        val (indexed, other) = (diff.map { it.path } + untracked).distinct().partition { Languages.languageOf(it) != null }
        val status = diff.associate { it.path to it.status }
        val files = indexed.map { ChangedFile(it, statusOf(status[it], it in untracked)) }
        val old = diff.filter { it.oldBlob != null && Languages.languageOf(it.path) != null }
        val beforeFile = if (old.isEmpty()) null else facts(repo, mergeBase, old)
        ChangeSet(worktree, repo.defaultRef, mergeBase, files, other, beforeFile)
    }

    private fun mergeBase(repo: RepoState, worktree: String): String {
        Git.run(worktree, "merge-base", "HEAD", repo.defaultRef, allowFail = true)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val shallow = Git.run(worktree, "rev-parse", "--is-shallow-repository", allowFail = true)?.trim() == "true"
        throw IllegalArgumentException(
            if (shallow) "$worktree is a shallow clone without the history back to ${repo.defaultRef}; fetch more of it (git fetch --unshallow)"
            else "$worktree has no commit in common with ${repo.defaultRef}",
        )
    }

    // Untracked again after `git rm --cached` while still on disk: changed, not deleted.
    private fun statusOf(diff: Char?, untracked: Boolean): Char = when {
        diff == null -> 'A'
        diff == 'D' && untracked -> 'M'
        diff == 'A' || diff == 'D' -> diff
        else -> 'M'
    }

    /**
     * The index of [mergeBase] holding at least the old versions of [old]. Calls for the same merge-base share one job
     * at a time; each checks afterwards what is still missing, since the job it joined may have been for other files.
     */
    private suspend fun facts(repo: RepoState, mergeBase: String, old: List<DiffEntry>): Path {
        val dir = repo.dir.resolve(DIR).also { Files.createDirectories(it) }
        val file = dir.resolve("${mergeBase.take(12)}.db")
        val deadline = System.currentTimeMillis() + waitMs
        var requested = emptySet<String>()
        var stalled = 0
        while (true) {
            if (!file.exists()) prune(dir)
            val missing = old.filter { it.path !in present(file) }
            if (missing.isEmpty()) return file
            // Once may be a joined job for other files; twice means git cannot give us these blobs.
            if (missing.map { it.path }.toSet() == requested && ++stalled >= 2) {
                throw IllegalStateException("git objects of ${missing.size} files at merge-base ${mergeBase.take(7)} are not in this clone (partial or shallow?)")
            }
            val job = start(repo, mergeBase, file, missing)
            requested = missing.map { it.path }.toSet()
            withTimeoutOrNull(maxOf(1, deadline - System.currentTimeMillis())) { job.await() }
                ?: throw BusyException("indexing ${missing.size} files of merge-base ${mergeBase.take(7)}; retry in a few seconds")
        }
    }

    private fun present(file: Path): Set<String> = Store.open(file).use { db ->
        db.createStatement().use { s -> s.executeQuery("SELECT path FROM files").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } }
    }

    private fun start(repo: RepoState, mergeBase: String, file: Path, missing: List<DiffEntry>): Deferred<*> {
        val sizes = Git.blobSizes(repo.commonDir, missing.map { it.oldBlob!! })
        val update = StoreUpdate(
            puts = missing.map { FilePut(it.path, blob = it.oldBlob, size = sizes[it.oldBlob] ?: 0) },
            meta = mapOf("commit" to mergeBase, "built_at" to IsoTime.now()),
        )
        val inline = InlineParse.fits(update.puts)
        return queue.run(if (inline) JobQueue.Lane.FAST else JobQueue.Lane.HEAVY, "merge-base:${repo.id}:$mergeBase") {
            withContext(Dispatchers.IO) {
                if (inline) Store.open(file).use { StoreUpdater.apply(it, update, repo.commonDir) } else launcher.update(repo.commonDir, null, file, update, repo.dir)
            }
        }
    }

    // Merge-bases move on as branches are rebased or merged; a few recent ones serve every open worktree.
    private fun prune(dir: Path) {
        val stores = dir.listDirectoryEntries("*.db").sortedByDescending { it.getLastModifiedTime() }
        for (store in stores.drop(KEEP - 1)) {
            for (suffix in listOf("", "-wal", "-shm")) runCatching { Files.deleteIfExists(dir.resolve(store.name + suffix)) }
        }
    }

    private companion object {
        const val DIR = "merge-bases"
        const val KEEP = 8
    }
}
