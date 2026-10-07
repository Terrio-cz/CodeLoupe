package codeloupe.git

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.platform.Timings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitObjectsTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private val commonDir = repo.resolve(".git").toString()

    @Test
    fun `refs, merge-bases and blobs read in-process match git`() {
        val first = git(repo, "rev-parse", "HEAD")
        git(repo, "checkout", "-q", "-b", "side")
        git(repo, "commit", "-q", "--allow-empty", "-m", "side")
        git(repo, "checkout", "-q", "main")
        git(repo, "commit", "-q", "--allow-empty", "-m", "main")
        git(repo, "tag", "v1", "side")
        git(repo, "update-ref", "refs/remotes/origin/main", "main")
        git(repo, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        val spawns = Timings.gitSpawns()

        assertEquals(git(repo, "rev-parse", "v1^{commit}"), GitObjects.resolve(commonDir, "v1"))
        assertEquals(git(repo, "rev-parse", "main"), GitObjects.resolve(commonDir, "origin/main"))
        assertNull(GitObjects.resolve(commonDir, "no-such-ref"))
        assertEquals("refs/remotes/origin/main", GitObjects.symbolic(commonDir, "refs/remotes/origin/HEAD"))
        assertEquals(first, GitObjects.mergeBase(commonDir, git(repo, "rev-parse", "main"), git(repo, "rev-parse", "side")))

        val blobs = git(repo, "ls-tree", "-r", "main").lines().map { it.split(' ', '\t')[2] }
        val sizes = GitObjects.blobSizes(commonDir, blobs)
        assertEquals(blobs.associateWith { git(repo, "cat-file", "-s", it).toLong() }, sizes)
        val texts = HashMap<String, String>()
        assertEquals(blobs.size, GitObjects.blobs(commonDir).read(blobs) { sha, text -> texts[sha] = text })
        assertEquals(blobs.associateWith { git(repo, "cat-file", "blob", it) }, texts.mapValues { it.value.trim() })
        assertEquals(spawns, Timings.gitSpawns(), "no git process")

        // Not in the object store: asked of git, which would fetch it in a partial clone; here it stays missing.
        val missing = "0".repeat(40)
        assertEquals(sizes, GitObjects.blobSizes(commonDir, blobs + missing))
        assertEquals(blobs.size, GitObjects.blobs(commonDir).read(blobs + missing) { _, _ -> })
    }

    @Test
    fun `a repository JGit cannot open is read with git`() {
        val sha256 = TestRepos.tmpDir("sha256")
        git(sha256, "init", "-q", "-b", "main", "--object-format=sha256")
        git(sha256, "commit", "-q", "--allow-empty", "-m", "one")
        git(sha256, "tag", "v1")
        val dir = sha256.resolve(".git").toString()
        assertEquals(git(sha256, "rev-parse", "v1^{commit}"), GitObjects.resolve(dir, "v1"))
        val head = git(sha256, "rev-parse", "HEAD")
        assertEquals(head, GitObjects.mergeBase(dir, head, head))
    }
}
