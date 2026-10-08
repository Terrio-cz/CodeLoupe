package codeloupe.taskcode

import codeloupe.git.JGitRepos
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter

/**
 * Reads the default branch's history in-process and records every commit whose subject names a task, with the files
 * it changed against its first parent. Incremental: a later scan walks only what the last one did not reach;
 * a rewritten history (the old tip no longer reachable) drops the records and starts over. Written in batches, so
 * an interrupted scan keeps what it did and the next one skips it.
 */
class HistoryScan(private val store: TaskCodeStore, private val pattern: TaskPattern, private val maxCommits: Int = MAX_COMMITS) {
    data class Result(val walked: Int, val recorded: Int, val truncated: Boolean)

    /** Brings the records of [ref] in the repository at [commonDir] up to its [tip]. */
    fun scan(commonDir: String, ref: String, tip: String, now: Long = System.currentTimeMillis()): Result = JGitRepos.read(commonDir) { repo ->
        RevWalk(repo).use { walk ->
            // Bodies are loaded one commit at a time: the walk must not keep every message of a long history in the heap.
            walk.isRetainBody = false
            val head = walk.parseCommit(ObjectId.fromString(tip))
            val old = store.scannedTip(ref)?.let { runCatching { walk.parseCommit(ObjectId.fromString(it)) }.getOrNull() }
            if (old != null && walk.isMergedInto(old, head)) {
                walk.reset()
                walk.markStart(head)
                walk.markUninteresting(old)
            } else {
                if (old != null || store.scannedTip(ref) != null) store.write(store::clear)
                walk.reset()
                walk.markStart(head)
            }
            var walked = 0
            var recorded = 0
            var truncated = false
            val batch = ArrayList<Record>()
            for (commit in walk) {
                if (++walked > maxCommits) {
                    truncated = true
                    break
                }
                record(repo, walk, commit)?.let { batch += it }
                if (batch.size >= BATCH) {
                    recorded += flush(batch)
                }
            }
            recorded += flush(batch)
            // A truncated scan leaves the tip unmarked: the next one walks again and skips what is recorded.
            if (!truncated) store.write { db -> store.markScanned(db, ref, tip, now) }
            Result(walked - if (truncated) 1 else 0, recorded, truncated)
        }
    }

    private fun record(repo: Repository, walk: RevWalk, commit: RevCommit): Record? {
        walk.parseBody(commit)
        val subject = commit.shortMessage
        commit.disposeBody()
        val tasks = pattern.idsIn(subject)
        if (tasks.isEmpty()) return null
        if (store.read { db -> store.known(db, commit.name) }) return null
        val merge = commit.parentCount > 1
        val included = !merge || mergedBranchCarries(walk, commit, tasks)
        val parent = commit.parents.firstOrNull()
        val files = if (included) diff(repo, walk, parent, commit) else emptyList()
        return Record(TaskCommit(commit.name, commit.commitTime * 1000L, subject, parent?.name, merge, included), tasks, files)
    }

    /** A merge is the task's own when the branch it merged in (its second parent) carries one of the same ids. */
    private fun mergedBranchCarries(walk: RevWalk, merge: RevCommit, tasks: List<String>): Boolean {
        val branch = merge.getParent(1)
        walk.parseBody(branch)
        val carried = pattern.idsIn(branch.shortMessage)
        branch.disposeBody()
        return carried.any { it in tasks }
    }

    private fun diff(repo: Repository, walk: RevWalk, parent: RevCommit?, commit: RevCommit): List<CommitFile> {
        walk.parseHeaders(commit)
        TreeWalk(repo).use { tree ->
            tree.isRecursive = true
            tree.filter = TreeFilter.ANY_DIFF
            if (parent == null) {
                tree.addTree(EmptyTreeIterator())
            } else {
                walk.parseHeaders(parent)
                tree.addTree(parent.tree)
            }
            tree.addTree(commit.tree)
            return DiffEntry.scan(tree).mapNotNull { entry ->
                // Submodule pointers are not files of this repository.
                if (entry.oldMode == FileMode.GITLINK || entry.newMode == FileMode.GITLINK) return@mapNotNull null
                val status = when (entry.changeType) {
                    DiffEntry.ChangeType.ADD -> 'A'
                    DiffEntry.ChangeType.DELETE -> 'D'
                    else -> 'M'
                }
                val path = if (status == 'D') entry.oldPath else entry.newPath
                CommitFile(path, status, entry.oldId?.toObjectId()?.name?.takeIf { status != 'A' }, entry.newId?.toObjectId()?.name?.takeIf { status != 'D' })
            }
        }
    }

    private fun flush(batch: MutableList<Record>): Int {
        if (batch.isEmpty()) return 0
        store.write { db -> batch.forEach { store.putCommit(db, it.commit, it.tasks, it.files) } }
        return batch.size.also { batch.clear() }
    }

    private class Record(val commit: TaskCommit, val tasks: List<String>, val files: List<CommitFile>)

    companion object {
        /** Enough for any repository CodeLoupe is meant for; a longer history is read newest first and reported as cut. */
        const val MAX_COMMITS = 50_000
        private const val BATCH = 200
    }
}
