package codeloupe.git

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
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
    private const val MISSING = ""

    // Repositories with many tags have megabyte-sized packed-refs: each version of the file is scanned once per
    // ref name asked for, and only those few answers are kept.
    private val packedLookups = ConcurrentHashMap<Path, PackedLookups>()

    /** Commit of HEAD in the worktree at [worktree]. */
    fun head(worktree: String): String? = runCatching {
        val gitDir = GitLayout.gitDir(Path.of(worktree)) ?: return null
        val commonDir = GitLayout.commonDir(gitDir).takeUnless(::isReftable) ?: return null
        resolveSymbolic(gitDir, commonDir, "HEAD", 0)
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

    /** Where the symbolic ref [ref] points (`refs/remotes/origin/main`); null when it is not one. Files backend only. */
    fun symbolic(commonDir: String, ref: String): String? = runCatching {
        val file = Path.of(commonDir).resolve(ref)
        if (!file.isRegularFile()) return null
        file.readText().trim().takeIf { it.startsWith("ref: ") }?.removePrefix("ref: ")?.trim()
    }.getOrNull()

    /** True when [commonDir] keeps refs as files (and packed-refs), the layout this reader understands. */
    fun filesBackend(commonDir: String): Boolean = !isReftable(Path.of(commonDir))

    /**
     * Branches checked out in every worktree of the repository at [path] (a worktree or the common dir); null when
     * the layout is not the files backend.
     */
    fun worktreeBranches(path: String): List<String>? = runCatching {
        val start = Path.of(path)
        val common = (GitLayout.gitDir(start) ?: start.takeIf { it.resolve("HEAD").isRegularFile() })?.let(GitLayout::commonDir)?.takeUnless(::isReftable) ?: return null
        val heads = listOf(common.resolve("HEAD")) + (common.resolve("worktrees").takeIf { it.isDirectory() }?.let { dir ->
            Files.list(dir).use { s -> s.map { it.resolve("HEAD") }.toList() }
        } ?: emptyList())
        heads.filter { it.isRegularFile() }.map { it.readText().trim() }.filter { it.startsWith("ref: refs/heads/") }.map { it.removePrefix("ref: refs/heads/") }
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
        val stamp = Files.getLastModifiedTime(file).toMillis() to Files.size(file)
        val lookups = packedLookups.compute(file) { _, old -> old?.takeIf { it.stamp == stamp } ?: PackedLookups(stamp) }!!
        return lookups.answers.getOrPut(ref) { scanPacked(file, ref) ?: MISSING }.takeUnless { it == MISSING }
    }

    private fun scanPacked(file: Path, ref: String): String? = Files.newBufferedReader(file).useLines { lines ->
        lines.firstOrNull { !it.startsWith("#") && !it.startsWith("^") && it.endsWith(" $ref") }?.substringBefore(' ')
    }

    /** Answers already read from one version of a packed-refs file, misses included. */
    private class PackedLookups(val stamp: Pair<Long, Long>) {
        val answers = ConcurrentHashMap<String, String>()
    }

    private fun isReftable(commonDir: Path) = commonDir.resolve("reftable").exists()
}
