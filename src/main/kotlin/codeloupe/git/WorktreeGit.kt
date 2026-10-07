package codeloupe.git

/** What git says about the files of one worktree; each call is one git process. Paths are relative to the worktree root. */
object WorktreeGit {
    /** Tracked paths whose content in the worktree differs from [commit]: modified, added to the index or deleted. */
    fun changedSince(worktree: String, commit: String): List<String> =
        names(Git.run(worktree, "diff", "--name-only", "-z", "--no-renames", commit, "--")!!)

    /** Untracked files git does not ignore. */
    fun untracked(worktree: String): List<String> = names(Git.run(worktree, "ls-files", "--others", "--exclude-standard", "-z")!!)

    /** Untracked directories git ignores as a whole (build output and the like). */
    fun ignoredDirs(worktree: String): Set<String> =
        names(Git.run(worktree, "ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z")!!)
            .filter { it.endsWith("/") }
            .mapTo(HashSet()) { it.removeSuffix("/") }

    /** The [paths] that git ignores. */
    fun ignored(worktree: String, paths: Collection<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        // Exit code 1 means "none ignored".
        val out = Git.run(worktree, "check-ignore", "-z", "--stdin", allowFail = true, input = paths.joinToString("\u0000", postfix = "\u0000"))
        return names(out.orEmpty()).toSet()
    }

    /** The [paths] marked skip-worktree: outside a sparse checkout, not deleted. */
    fun skipWorktree(worktree: String, paths: Collection<String>): Set<String> {
        // Batched to stay under Windows' command-line limit; `:(literal)` keeps names with * or ? from matching others.
        return paths.chunked(PATHS_PER_CALL).flatMapTo(HashSet()) { batch ->
            val out = Git.run(worktree, "ls-files", "-t", "-z", "--", *batch.map { ":(literal)$it" }.toTypedArray())!!
            names(out).filter { it.startsWith("S ") }.map { it.substring(2) }
        }
    }

    /** Paths of the worktrees git knows for the repository at [commonDir], main worktree included. */
    fun list(commonDir: String): List<String> =
        Git.run(commonDir, "worktree", "list", "--porcelain", "-z")!!.split('\u0000')
            .filter { it.startsWith("worktree ") }
            .map { it.removePrefix("worktree ") }

    private fun names(out: String): List<String> = out.split('\u0000').filter { it.isNotEmpty() }

    private const val PATHS_PER_CALL = 100
}
