package codeloupe.taskcode

import codeloupe.git.GitLayout
import codeloupe.git.WorktreeGit
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** Which worktree of a repository has which branch checked out, from the HEAD files; git runs only for an unknown layout. */
object WorktreeBranches {
    /** Branch name → worktree path (unix separators), for every worktree of the repository at [commonDir] on a branch. */
    fun of(commonDir: String): Map<String, String> {
        val worktrees = GitLayout.worktrees(commonDir) ?: runCatching { WorktreeGit.list(commonDir) }.getOrDefault(emptyList())
        return worktrees.mapNotNull { worktree -> branch(Path.of(worktree))?.let { it to worktree.replace('\\', '/') } }.toMap()
    }

    private fun branch(worktree: Path): String? {
        val head = GitLayout.gitDir(worktree)?.resolve("HEAD")?.takeIf { it.isRegularFile() } ?: return null
        return runCatching { head.readText().trim() }.getOrNull()?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")
    }
}
