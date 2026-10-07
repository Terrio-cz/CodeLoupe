package codeloupe.tracker.read

import codeloupe.git.Git
import codeloupe.git.RefReader

/**
 * Tasks someone is working on, judged by the branch names of git worktrees (`TER-5`, `feature/TER-5-login`). The
 * HEAD files are read directly; git runs only for a layout the reader does not know.
 */
object WorktreeHolds {
    private val ID = Regex("(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9_]*-\\d+)(?![0-9])")

    /** Issue id (upper case) → the worktree branch that holds it, over every repository in [repos]. */
    fun held(repos: Collection<String>): Map<String, String> =
        repos.distinct().flatMap { repo -> RefReader.worktreeBranches(repo) ?: viaGit(repo) }
            .flatMap { branch -> ID.findAll(branch).map { it.groupValues[1].uppercase() to branch } }
            .toMap()

    private fun viaGit(repo: String): List<String> =
        runCatching { Git.run(repo, "worktree", "list", "--porcelain", allowFail = true) }.getOrNull().orEmpty().lines()
            .filter { it.startsWith("branch refs/heads/") }.map { it.removePrefix("branch refs/heads/") }
}
