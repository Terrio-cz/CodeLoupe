package codeloupe.git

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefReaderTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private val commonDir = repo.resolve(".git").toString()

    @Test
    fun `HEAD and branches resolve like git, loose and packed`() {
        val head = git(repo, "rev-parse", "HEAD")
        assertEquals(head, RefReader.head(repo.toString()))
        assertEquals(head, RefReader.branch(commonDir, "main"))
        git(repo, "pack-refs", "--all")
        assertEquals(head, RefReader.branch(commonDir, "refs/heads/main"))
        assertEquals(head, RefReader.head(repo.toString()))
        git(repo, "commit", "-q", "--allow-empty", "-m", "next")
        git(repo, "pack-refs", "--all")
        assertEquals(git(repo, "rev-parse", "HEAD"), RefReader.branch(commonDir, "main"), "a rewritten packed-refs is read again")
    }

    @Test
    fun `linked worktrees, detached HEAD and remote HEAD`() {
        val first = git(repo, "rev-parse", "HEAD")
        val worktree = TestRepos.tmpDir("wt").resolve("feature")
        git(repo, "worktree", "add", "-q", "-b", "feature", worktree.toString())
        git(worktree, "commit", "-q", "--allow-empty", "-m", "next")
        assertEquals(git(worktree, "rev-parse", "HEAD"), RefReader.head(worktree.toString()))
        assertEquals(first, RefReader.head(repo.toString()))
        git(repo, "update-ref", "refs/remotes/origin/main", first)
        git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        assertEquals(first, RefReader.branch(commonDir, "origin/main"))
        assertEquals(first, RefReader.branch(commonDir, "origin"))
        git(worktree, "checkout", "-q", "--detach", first)
        assertEquals(first, RefReader.head(worktree.toString()))
    }

    @Test
    fun `ambiguous names and missing refs are left to git`() {
        git(repo, "tag", "main")
        assertNull(RefReader.branch(commonDir, "main"))
        assertNull(RefReader.branch(commonDir, "no-such-branch"))
        assertNull(RefReader.head(TestRepos.tmpDir("nogit").toString()))
    }
}
