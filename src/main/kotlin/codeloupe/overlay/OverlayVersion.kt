package codeloupe.overlay

import java.nio.file.Path

/** What a query reads for a worktree: the overlay [file] (null when it holds nothing) against [base], at [version]. */
data class OverlayVersion(val file: Path?, val base: String, val version: Long)
