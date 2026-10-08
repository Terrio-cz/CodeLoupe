package codeloupe.git

import java.nio.file.Path

/** A worktree as the repository registered it: its root (unix separators) and its admin dir holding `HEAD` and `index`. */
data class WorktreeRegistration(val path: String, val adminDir: Path)
