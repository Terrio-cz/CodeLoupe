package codeloupe.tools

/** The key a tool remembers a caller's reads under: the caller's root, since a window works in one worktree. */
object Sessions {
    fun key(root: String): String = root.trim().replace('\\', '/').trimEnd('/').lowercase()
}
