package codeloupe.tools

/** The key a tool remembers a caller's reads under: the caller's root, since a window works in one worktree. */
object Sessions {
    fun key(root: String): String = root.trim().replace('\\', '/').trimEnd('/').lowercase()

    /** Whether a caller keyed [key] works in [path] or around it: the key is the path, or one of them lies inside the other. */
    fun near(key: String, path: String): Boolean {
        val there = key(path)
        return there.isNotEmpty() && (key == there || key.startsWith("$there/") || there.startsWith("$key/"))
    }
}
