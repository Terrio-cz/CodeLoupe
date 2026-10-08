package codeloupe.uiapi

import codeloupe.platform.NativeCalls
import codeloupe.platform.Sha1
import java.nio.file.Path

/** The id of a worktree in the UI API: the first 12 hex of the SHA-1 of its normalized path (docs/ui-spec.md § 9.1). */
internal object WorktreeId {
    fun of(path: String): String {
        val normal = Path.of(path).toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
        return Sha1.hex(if (NativeCalls.isWindows) normal.lowercase() else normal).take(12)
    }

    /** [root] is the worktree [worktree] or a directory inside it. */
    fun contains(worktree: String, root: String): Boolean {
        val w = key(worktree)
        val r = key(root)
        return r == w || r.startsWith("$w/")
    }

    private fun key(path: String): String {
        val normal = path.replace('\\', '/').trimEnd('/')
        return if (NativeCalls.isWindows) normal.lowercase() else normal
    }
}
