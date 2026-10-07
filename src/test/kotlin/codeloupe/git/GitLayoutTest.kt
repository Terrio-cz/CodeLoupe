package codeloupe.git

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitLayoutTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample")

    @Test
    fun `worktree and common dir match git, from the root, a subdirectory and a linked worktree`() {
        val linked = TestRepos.tmpDir("wt").resolve("feature")
        git(repo, "worktree", "add", "-q", "-b", "feature", linked.toString())
        for (path in listOf(repo, repo.resolve("src/main"), linked, linked.resolve("src"))) {
            assertEquals(fromGit(path), GitLayout.locate(path), "$path")
        }
        assertEquals(git(repo, "worktree", "list", "--porcelain").lines().filter { it.startsWith("worktree ") }.map { it.removePrefix("worktree ") },
            GitLayout.worktrees(fromGit(repo).commonDir))
    }

    @Test
    fun `layouts it does not read itself are left to git`() {
        assertNull(GitLayout.locate(repo.resolve(".git/refs")), "inside the git dir")
        assertNull(GitLayout.locate(TestRepos.tmpDir("nogit")), "no repository")
        val moved = TestRepos.tmpDir("moved").resolve("tree").also { it.createDirectories() }
        git(repo, "config", "core.worktree", moved.toString())
        assertNull(GitLayout.locate(repo), "core.worktree")
    }

    private fun fromGit(path: Path): WorktreeDirs {
        val (worktree, common) = git(path, "rev-parse", "--path-format=absolute", "--show-toplevel", "--git-common-dir").lines()
        return WorktreeDirs(Path.of(worktree).toRealPath().unix(), Path.of(common).toRealPath().unix())
    }

    private fun Path.unix() = toString().replace('\\', '/')
}
