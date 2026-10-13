package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.workspace.WorkspaceState
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorktreeRemoverTest {
    private val repo: Path = TestRepos.tmpDir("wt-repo").also { dir ->
        git(dir, "init", "-q", "-b", "main")
        dir.resolve("a.txt").writeText("a\n")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "base")
    }
    private val gitDir = repo.resolve(".git").toString().replace('\\', '/')

    /** A worktree on branch [name] with one commit, merged into main when [merge]. */
    private fun worktree(name: String, merge: Boolean = true): Path {
        val dir = TestRepos.tmpDir("wt").resolve(name)
        git(repo, "worktree", "add", "-q", "-b", name, dir.toString())
        dir.resolve("$name.txt").writeText("$name\n")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "$name work")
        if (merge) git(repo, "merge", "-q", "--ff-only", name)
        return dir
    }

    private fun entry(dir: Path, name: String, head: String = git(dir, "rev-parse", "HEAD"), lastError: String? = null) = PlanEntry(
        "worktree:$dir", TargetKind.WORKTREE, dir.toString(), "repo", name, null, WorkspaceState.LANDED, Verdict.AUTO, "landed",
        path = dir.toString(), gitDir = gitDir, branch = name, head = head, lastError = lastError,
    )

    private fun branches() = git(repo, "branch", "--format=%(refname:short)").lines().toSet()

    @Test
    fun `a clean merged worktree goes with its branch`() {
        val dir = worktree("CL-1")
        val outcome = WorktreeRemover().remove(entry(dir, "CL-1"))
        assertEquals(WorktreeRemover.Outcome.Removed("branch CL-1 deleted"), outcome)
        assertFalse(dir.exists())
        assertEquals(setOf("main"), branches())
        assertFalse(git(repo, "worktree", "list").contains("CL-1"))
    }

    @Test
    fun `changed or untracked files keep the worktree`() {
        val changed = worktree("CL-2")
        changed.resolve("a.txt").writeText("changed\n")
        val untracked = worktree("CL-3")
        untracked.resolve("new.txt").writeText("new\n")
        assertIs<WorktreeRemover.Outcome.Blocked>(WorktreeRemover().remove(entry(changed, "CL-2")))
        assertIs<WorktreeRemover.Outcome.Blocked>(WorktreeRemover().remove(entry(untracked, "CL-3")))
        assertTrue(changed.resolve("a.txt").exists() && untracked.resolve("new.txt").exists())
        assertTrue(branches().containsAll(setOf("CL-2", "CL-3")))
    }

    @Test
    fun `a worktree that moved since the plan or is locked stays`() {
        val moved = worktree("CL-4")
        val planned = git(moved, "rev-parse", "HEAD~1")
        val locked = worktree("CL-5")
        git(repo, "worktree", "lock", locked.toString())
        val first = WorktreeRemover().remove(entry(moved, "CL-4", head = planned))
        val second = WorktreeRemover().remove(entry(locked, "CL-5"))
        assertTrue(first is WorktreeRemover.Outcome.Blocked && "moved" in first.reason)
        assertTrue(second is WorktreeRemover.Outcome.Blocked && "locked" in second.reason)
        assertTrue(moved.exists() && locked.exists())
    }

    @Test
    fun `a branch the default branch does not contain is kept`() {
        val dir = worktree("CL-6", merge = false)
        val outcome = WorktreeRemover().remove(entry(dir, "CL-6"))
        assertEquals(WorktreeRemover.Outcome.Removed("branch CL-6 kept: not contained in main"), outcome)
        assertFalse(dir.exists())
        assertTrue("CL-6" in branches())
    }

    @Test
    fun `a removal a lock interrupts is reported and finished by the next attempt`() {
        val dir = worktree("CL-7")
        val head = git(dir, "rev-parse", "HEAD")
        var locked = true
        val remover = WorktreeRemover { path -> if (locked) throw IOException("held") else DirectoryRemover.remove(path) }
        // git worktree remove deletes the directory itself, so the fallback is reached only through a directory git left.
        dir.resolve(".git").toFile().delete()
        val first = remover.remove(entry(dir, "CL-7", head = head))
        assertTrue(first is WorktreeRemover.Outcome.Blocked && WorktreeRemover.PARTIAL !in first.reason, "an unreadable worktree is not touched: $first")

        val second = remover.remove(entry(dir, "CL-7", head = head, lastError = "blocked: ${WorktreeRemover.PARTIAL} held"))
        assertTrue(second is WorktreeRemover.Outcome.Blocked && WorktreeRemover.PARTIAL in second.reason)
        locked = false
        assertIs<WorktreeRemover.Outcome.Removed>(remover.remove(entry(dir, "CL-7", head = head, lastError = "blocked: ${WorktreeRemover.PARTIAL} held")))
        assertFalse(dir.exists())
        assertFalse(git(repo, "worktree", "list").contains("CL-7"))
    }

    @Test
    fun `a worktree already gone is pruned and reported gone`() {
        val dir = worktree("CL-8")
        DirectoryRemover.remove(dir)
        assertEquals(WorktreeRemover.Outcome.Gone, WorktreeRemover().remove(entry(dir, "CL-8", head = git(repo, "rev-parse", "CL-8"))))
        assertFalse(git(repo, "worktree", "list").contains("CL-8"))
        assertEquals(setOf("main"), branches())
    }
}
