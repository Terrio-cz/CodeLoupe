package codeloupe.git

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Where a repository keeps its files, read from `.git`, `gitdir:` and `commondir` like git does, without a git
 * process (~40–100 ms on Windows). Returns null for anything less plain — git environment variables, `core.worktree`,
 * bare repositories, paths inside a git dir — and the caller asks git.
 */
object GitLayout {
    private val CORE_WORKTREE = Regex("""(?im)^\s*worktree\s*=""")
    private val BARE = Regex("""(?im)^\s*bare\s*=\s*true\s*$""")
    private val GIT_ENV = listOf("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_CEILING_DIRECTORIES")

    /** The worktree containing [path] and its common dir. */
    fun locate(path: Path): WorktreeDirs? = runCatching {
        if (GIT_ENV.any { System.getenv(it) != null }) return null
        var dir: Path? = path.toRealPath()
        while (dir != null) {
            // git refuses to treat a path inside a git dir as part of a worktree.
            if (dir.name == ".git") return null
            val dotGit = dir.resolve(".git")
            val gitDir = when {
                dotGit.isDirectory() -> dotGit
                dotGit.isRegularFile() -> gitDir(dir) ?: return null
                else -> null
            }
            if (gitDir != null) return plain(dir, gitDir)
            dir = dir.parent
        }
        null
    }.getOrNull()

    /**
     * Every worktree the repository at [commonDir] has registered, the main one first, as `git worktree list` gives them
     * (a worktree whose directory is gone is still listed); null when the layout is not the plain one (a bare repository).
     */
    fun registrations(commonDir: String): List<WorktreeRegistration>? = runCatching {
        val common = Path.of(commonDir)
        if (common.name != ".git" || BARE.containsMatchIn(config(common))) return null
        val admin = common.resolve("worktrees")
        val linked = if (!admin.isDirectory()) emptyList() else admin.listDirectoryEntries().sortedBy { it.name }.mapNotNull { entry ->
            // `gitdir` names the worktree's .git file, absolute or (worktree.useRelativePaths) relative to this entry.
            val pointer = entry.resolve("gitdir").takeIf { it.isRegularFile() } ?: return@mapNotNull null
            WorktreeRegistration(real(entry.resolve(pointer.readText().trim()).normalize().parent), entry)
        }
        // Real paths, as locate() gives them: a worktree reached through a junction is still the same worktree.
        listOf(WorktreeRegistration(real(common.parent), common)) + linked
    }.getOrNull()

    /** Paths of every worktree of the repository at [commonDir], the main one first. */
    fun worktrees(commonDir: String): List<String>? = registrations(commonDir)?.map { it.path }

    /** The git dir of the worktree rooted at [worktree]: `.git` itself, or what a `.git` file points to. */
    fun gitDir(worktree: Path): Path? {
        val dotGit = worktree.resolve(".git")
        if (dotGit.isDirectory()) return dotGit
        if (!dotGit.isRegularFile()) return null
        val target = dotGit.readText().trim().takeIf { it.startsWith("gitdir:") }?.removePrefix("gitdir:")?.trim() ?: return null
        return worktree.resolve(target).normalize()
    }

    /** The common dir of [gitDir]: a linked worktree's `commondir` points to it, otherwise it is [gitDir] itself. */
    fun commonDir(gitDir: Path): Path {
        val pointer = gitDir.resolve("commondir")
        return if (pointer.isRegularFile()) gitDir.resolve(pointer.readText().trim()).normalize() else gitDir
    }

    private fun plain(worktree: Path, gitDir: Path): WorktreeDirs? {
        if (!gitDir.resolve("HEAD").exists()) return null
        val common = commonDir(gitDir)
        val config = config(common)
        if (CORE_WORKTREE.containsMatchIn(config) || BARE.containsMatchIn(config) || gitDir.resolve("config.worktree").exists()) return null
        return WorktreeDirs(unix(worktree.toRealPath()), unix(common.toRealPath()))
    }

    private fun config(commonDir: Path): String = commonDir.resolve("config").takeIf { it.isRegularFile() }?.readText().orEmpty()

    private fun real(path: Path) = unix(runCatching { path.toRealPath() }.getOrDefault(path))

    private fun unix(path: Path) = path.toString().replace('\\', '/')
}
