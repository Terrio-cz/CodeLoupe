package codeloupe.git

import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * Thin git helpers. Everything goes through the git CLI so any repository layout (worktrees, submodules,
 * sparse checkouts) behaves as git itself sees it.
 */
object Git {
    /** stdout of `git -C cwd args`; null when git fails and [allowFail] is set. */
    fun run(cwd: String, vararg args: String, allowFail: Boolean = false): String? {
        val process = start(cwd, *args)
        process.outputStream.close()
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        val stdout = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code == 0) return stdout
        if (allowFail) return null
        val reason = stderr.join().trim().lines().last()
        throw GitException("git ${args.joinToString(" ")}: $reason")
    }

    fun start(cwd: String, vararg args: String): Process =
        try {
            ProcessBuilder(listOf("git", "-C", cwd) + args).start()
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
}
