package codeloupe.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Resolves HEAD and branch names by reading the files-backend ref store directly: a query must not pay for a
 * git process (~30 ms on Windows). Returns null whenever the layout is anything else (reftable, tags, odd
 * refs); the caller then asks git.
 */
object RefReader {
    private val SHA = Regex("[0-9a-f]{40}([0-9a-f]{24})?")
    private const val MAX_SYMREF_DEPTH = 5

    /** Commit of HEAD in the worktree at [worktree]. */
    fun head(worktree: String): String? = runCatching {
        val gitDir = gitDir(Path.of(worktree)) ?: return null
        resolveSymbolic(gitDir, commonDir(gitDir) ?: return null, "HEAD", 0)
    }.getOrNull()

    /** Commit a branch name points to, in git's own lookup order (`main`, `origin/main`, `refs/heads/main`, `HEAD`). */
    fun branch(commonDir: String, name: String): String? = runCatching {
        val common = Path.of(commonDir)
        if (isReftable(common)) return null
        if (name == "HEAD") return resolveSymbolic(common, common, "HEAD", 0)
        // git prefers refs/<name> and tags over branches; leave those rare cases to git.
        if (!name.startsWith("refs/") && listOf("refs/$name", "refs/tags/$name").any { exists(common, it) }) return null
        candidates(name).firstNotNullOfOrNull { resolveSymbolic(common, common, it, 0) }
    }.getOrNull()

    private fun candidates(name: String): List<String> =
        if (name.startsWith("refs/")) listOf(name) else listOf("refs/heads/$name", "refs/remotes/$name", "refs/remotes/$name/HEAD")

    private fun resolveSymbolic(gitDir: Path, commonDir: Path, ref: String, depth: Int): String? {
        if (depth > MAX_SYMREF_DEPTH || !(ref == "HEAD" || ref.startsWith("refs/heads/") || ref.startsWith("refs/remotes/"))) return null
        // HEAD is per worktree; branches live in the common dir.
        val loose = (if (ref == "HEAD") gitDir else commonDir).resolve(ref)
        val value = if (loose.isRegularFile()) loose.readText().trim() else packed(commonDir, ref) ?: return null
        return when {
            value.startsWith("ref: ") -> resolveSymbolic(gitDir, commonDir, value.removePrefix("ref: ").trim(), depth + 1)
            SHA.matches(value) -> value
            else -> null
        }
    }

    private fun exists(commonDir: Path, ref: String) = commonDir.resolve(ref).isRegularFile() || packed(commonDir, ref) != null

    private fun packed(commonDir: Path, ref: String): String? {
        val file = commonDir.resolve("packed-refs")
        if (!file.exists()) return null
        return Files.newBufferedReader(file).useLines { lines ->
            lines.firstOrNull { !it.startsWith("#") && !it.startsWith("^") && it.endsWith(" $ref") }?.substringBefore(' ')
        }
    }

    /** `.git` is the git dir itself, or a file `gitdir: <path>` in a linked worktree. */
    private fun gitDir(worktree: Path): Path? {
        val dotGit = worktree.resolve(".git")
        if (dotGit.isDirectory()) return dotGit
        if (!dotGit.isRegularFile()) return null
        val target = dotGit.readText().trim().removePrefix("gitdir: ").trim()
        return worktree.resolve(target).normalize()
    }

    private fun commonDir(gitDir: Path): Path? {
        val pointer = gitDir.resolve("commondir")
        val common = if (pointer.isRegularFile()) gitDir.resolve(pointer.readText().trim()).normalize() else gitDir
        return common.takeUnless(::isReftable)
    }

    private fun isReftable(commonDir: Path) = commonDir.resolve("reftable").exists()
}
