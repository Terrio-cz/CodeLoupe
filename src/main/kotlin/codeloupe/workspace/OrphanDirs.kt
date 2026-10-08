package codeloupe.workspace

import codeloupe.git.GitLayout
import codeloupe.platform.IsoTime
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Directories under a worktree root that the repository has no worktree registered for: what is left after
 * `git worktree remove` failed on a locked build directory, or after the registration was pruned by hand. A separate
 * clone or another repository's worktree is listed too, with that as its note: it does not belong here either.
 */
internal object OrphanDirs {
    /** The orphan directories directly under [root]; [registered] holds the [key]s of the repository's worktrees. */
    fun under(root: String, commonDir: String, registered: Set<String>): List<Workspace> {
        val dir = Path.of(root)
        if (!dir.isDirectory()) return emptyList()
        return dir.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }.mapNotNull { child ->
            if (key(child) in registered) return@mapNotNull null
            val reason = reason(child, commonDir)
            Workspace(
                path = child.toString().replace('\\', '/'), name = child.name, role = "directory", state = WorkspaceState.ORPHAN, note = reason,
                lastActivity = runCatching { IsoTime.of(Files.getLastModifiedTime(child).toInstant()) }.getOrNull(),
            )
        }
    }

    /** A path in the form worktree paths are compared in: real, with `/`, and without case on Windows. */
    fun key(path: Path): String {
        val real = runCatching { path.toRealPath() }.getOrDefault(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
        return if (File.separatorChar == '\\') real.lowercase() else real
    }

    private fun reason(child: Path, commonDir: String): String {
        val dotGit = child.resolve(".git")
        return when {
            dotGit.isDirectory() -> "a separate clone (.git directory), not a worktree of this repository"
            !dotGit.isRegularFile() -> "no .git: not a worktree, git has no registration for it"
            else -> {
                val gitDir = GitLayout.gitDir(child)
                when {
                    gitDir == null -> ".git file points nowhere"
                    !Files.isDirectory(gitDir) -> "its registration is gone (git worktree prune removed ${gitDir.name})"
                    key(GitLayout.commonDir(gitDir)) != key(Path.of(commonDir)) -> "a worktree of another repository (${GitLayout.commonDir(gitDir).toString().replace('\\', '/')})"
                    else -> "git no longer lists it"
                }
            }
        }
    }
}
