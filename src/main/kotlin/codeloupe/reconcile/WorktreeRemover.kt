package codeloupe.reconcile

import codeloupe.git.Git
import codeloupe.git.GitObjects
import codeloupe.git.WorktreeGit
import codeloupe.repo.DefaultRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Removes a finished worktree and its merged branch. Nothing is forced: a worktree that moved since the plan, is locked
 * (`git worktree lock`) or holds changes or untracked files stays. When `git worktree remove` cannot finish (on Windows a
 * process still holds a file), the clean directory is deleted the same way and the registration pruned; a deletion a lock
 * interrupts is reported as [PARTIAL] and finished on the next attempt without the checks git can no longer answer.
 */
class WorktreeRemover(private val removeDirectory: (Path) -> Unit = DirectoryRemover::remove) {
    sealed interface Outcome {
        data class Removed(val detail: String) : Outcome
        data object Gone : Outcome
        data class Blocked(val reason: String) : Outcome
    }

    fun remove(entry: PlanEntry): Outcome {
        val gitDir = entry.gitDir ?: return Outcome.Blocked("no git directory in the plan")
        val path = entry.path ?: return Outcome.Blocked("no path in the plan")
        val dir = Path.of(path)
        if (!Files.exists(dir)) {
            Git.run(gitDir, "worktree", "prune", allowFail = true)
            return Outcome.Gone.also { deleteBranch(gitDir, entry) }
        }
        val resumed = entry.lastError?.contains(PARTIAL) == true
        if (!resumed) {
            refusal(gitDir, path, entry)?.let { return Outcome.Blocked(it) }
            if (Git.run(gitDir, "worktree", "remove", path, allowFail = true) == null) {
                // A refusal for changes made since the check above wins over the fallback.
                refusal(gitDir, path, entry)?.let { return Outcome.Blocked(it) }
            }
        }
        if (Files.exists(dir)) {
            try {
                removeDirectory(dir)
            } catch (e: IOException) {
                return Outcome.Blocked("$PARTIAL ${e::class.simpleName}: ${e.message.orEmpty().lineSequence().first()}")
            }
        }
        Git.run(gitDir, "worktree", "prune", allowFail = true)
        return Outcome.Removed(deleteBranch(gitDir, entry))
    }

    // Why the worktree must stay, or null when git would remove it without losing anything.
    private fun refusal(gitDir: String, path: String, entry: PlanEntry): String? {
        val head = Git.run(path, "rev-parse", "HEAD", allowFail = true)?.trim() ?: return "git cannot read the worktree"
        if (entry.head != null && head != entry.head) return "its HEAD moved to ${head.take(12)} after the plan was made"
        if (locked(gitDir, path)) return "it is locked (git worktree lock)"
        val changed = WorktreeGit.changedSince(path, "HEAD").size + WorktreeGit.untracked(path).size
        return if (changed > 0) "$changed changed or untracked file(s); nothing removed" else null
    }

    private fun locked(gitDir: String, path: String): Boolean {
        val wanted = path.replace('\\', '/').trimEnd('/').lowercase()
        val blocks = Git.run(gitDir, "worktree", "list", "--porcelain")!!.split("\n\n")
        return blocks.any { block ->
            val lines = block.lines()
            lines.firstOrNull()?.removePrefix("worktree ")?.replace('\\', '/')?.trimEnd('/')?.lowercase() == wanted && lines.any { it.startsWith("locked") }
        }
    }

    // Only a branch still at the planned commit and contained in the default branch goes; update-ref checks the commit itself.
    private fun deleteBranch(gitDir: String, entry: PlanEntry): String {
        val branch = entry.branch ?: return "no branch"
        val tip = GitObjects.resolve(gitDir, "refs/heads/$branch") ?: return "branch $branch is gone"
        if (entry.head != null && tip != entry.head) return "branch $branch kept: it moved after the plan was made"
        val base = DefaultRef.of(gitDir)
        if (Git.run(gitDir, "merge-base", "--is-ancestor", tip, base, allowFail = true) == null) return "branch $branch kept: not contained in $base"
        return if (Git.run(gitDir, "update-ref", "-d", "refs/heads/$branch", tip, allowFail = true) != null) "branch $branch deleted" else "branch $branch kept: git refused to delete it"
    }

    companion object {
        /** Marks an attempt that deleted part of a clean worktree: the next one finishes it. */
        const val PARTIAL = "removal interrupted:"
    }
}
