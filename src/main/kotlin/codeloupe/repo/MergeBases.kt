package codeloupe.repo

import codeloupe.changes.ChangeSet
import codeloupe.changes.ChangedFile
import codeloupe.daemon.JobQueue
import codeloupe.git.DiffEntry
import codeloupe.git.Git
import codeloupe.git.GitObjects
import codeloupe.git.RefReader
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
import java.sql.SQLException
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
    /** [withBefore] false skips the merge-base index: the caller wants the changed paths only. */
    suspend fun changes(repo: RepoState, worktree: String, withBefore: Boolean = true): ChangeSet = withContext(Dispatchers.IO) {
        val mergeBase = mergeBase(repo, worktree)
        val diff = DiffEntry.parse(Git.run(worktree, "diff", "--raw", "-z", "--no-renames", "--no-abbrev", mergeBase, "--")!!)
        val untracked = WorktreeGit.untracked(worktree).toSet()
        val (indexed, other) = (diff.map { it.path } + untracked).distinct().partition { Languages.languageOf(it) != null }
        val status = diff.associate { it.path to it.status }
        val files = indexed.map { ChangedFile(it, statusOf(status[it], it in untracked)) }
        val old = diff.filter { it.oldBlob != null && Languages.languageOf(it.path) != null }
        val beforeFile = if (old.isEmpty() || !withBefore) null else facts(repo, mergeBase, old)
        ChangeSet(worktree, repo.defaultRef, mergeBase, files, other, beforeFile)
    }

    private fun mergeBase(repo: RepoState, worktree: String): String {
        val head = RefReader.head(worktree)
        val target = GitObjects.resolve(repo.commonDir, repo.defaultRef)
        val base = if (head != null && target != null) {
            GitObjects.mergeBase(repo.commonDir, head, target)
        } else {
            Git.run(worktree, "merge-base", "HEAD", repo.defaultRef, allowFail = true)?.trim()?.ifEmpty { null }
        }
        if (base != null) return base
        val shallow = Path.of(repo.commonDir, "shallow").exists()
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
        while (true) {
            if (!file.exists()) prune(dir)
            val missing = old.filter { it.path !in present(file) }
            if (missing.isEmpty()) return file
            val job = start(repo, mergeBase, file, missing)
            withTimeoutOrNull(maxOf(1, deadline - System.currentTimeMillis())) { job.await() }
                ?: throw BusyException("indexing ${missing.size} files of merge-base ${mergeBase.take(7)}; retry in a few seconds")
        }
    }

    // Read-only: a writable open sets the journal mode, which fails with SQLITE_BUSY while another call is writing.
    private fun present(file: Path): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            Store.open(file, readOnly = true).use { db ->
                db.createStatement().use { s -> s.executeQuery("SELECT path FROM files").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } }
            }
        } catch (e: SQLException) {
            // Just created by a concurrent call that has not written its schema yet: nothing is in it.
            if (e.message?.contains("no such table") == true) emptySet() else throw e
        }
    }

    private fun start(repo: RepoState, mergeBase: String, file: Path, missing: List<DiffEntry>): Deferred<*> {
        val sizes = GitObjects.blobSizes(repo.commonDir, missing.map { it.oldBlob!! })
        // A blob git does not have (a partial or shallow clone) would be asked for again on every call.
        val absent = missing.count { it.oldBlob !in sizes }
        if (absent > 0) throw IllegalStateException("git objects of $absent files at merge-base ${mergeBase.take(7)} are not in this clone (partial or shallow?)")
        val update = StoreUpdate(
            puts = missing.map { FilePut(it.path, blob = it.oldBlob, size = sizes[it.oldBlob] ?: 0) },
            meta = mapOf("commit" to mergeBase, "built_at" to IsoTime.now()),
        )
        val inline = InlineParse.fits(update.puts)
        return queue.run(if (inline) JobQueue.Lane.FAST else JobQueue.Lane.HEAVY, "merge-base:${repo.id}:$mergeBase") {
            withContext(Dispatchers.IO) {
                if (inline) Store.open(file).use { StoreUpdater.apply(it, update, GitObjects.blobs(repo.commonDir)) } else launcher.update(repo.commonDir, null, file, update, repo.dir)
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
