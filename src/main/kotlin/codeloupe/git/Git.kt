package codeloupe.git

import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * Thin git helpers. Everything goes through the git CLI so any repository layout (worktrees, submodules,
 * sparse checkouts) behaves as git itself sees it.
 */
object Git {
    /** stdout of `git -C cwd args` (with [input] on stdin); null when git fails and [allowFail] is set. */
    fun run(cwd: String, vararg args: String, allowFail: Boolean = false, input: String? = null): String? {
        val process = start(cwd, *args)
        val writer = Thread.ofVirtual().start { runCatching { process.outputStream.use { out -> input?.let { out.write(it.toByteArray()) } } } }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        writer.join()
        if (code == 0) return stdout
        if (allowFail) return null
        val reason = stderr.join().trim().lines().last()
        throw GitException("git ${args.joinToString(" ")}: $reason")
    }

    // The daemon only reads: --no-optional-locks keeps it from refreshing a worktree's index under the user's feet.
    // A repository's own config must not make a query run a command: core.fsmonitor can name one.
    fun start(cwd: String, vararg args: String): Process =
        try {
            ProcessBuilder(listOf("git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-C", cwd) + args).start()
        } catch (e: IOException) {
            throw GitException("git is not available: ${e.message}")
        }

    /** Every blob of a commit. */
    fun lsTree(cwd: String, commit: String): List<TreeEntry> =
        run(cwd, "ls-tree", "-r", "-z", "--long", "--full-tree", commit)!!
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .map(TreeEntry::parse)
            .filter { it.type == "blob" }

    /** Size in bytes of each blob, read from the object store without the content. */
    fun blobSizes(cwd: String, shas: Collection<String>): Map<String, Long> {
        if (shas.isEmpty()) return emptyMap()
        return run(cwd, "cat-file", "--batch-check", input = shas.joinToString("\n", postfix = "\n"))!!
            .lines()
            .map { it.split(' ') }
            .filter { it.size == 3 && it[1] == "blob" }
            .associate { it[0] to it[2].toLong() }
    }

    /** Files that differ between two commits. */
    fun diff(cwd: String, from: String, to: String): List<DiffEntry> =
        DiffEntry.parse(run(cwd, "diff", "--raw", "-z", "--no-renames", "--no-abbrev", from, to, "--")!!)
}
