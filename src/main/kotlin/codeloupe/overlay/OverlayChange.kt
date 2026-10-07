package codeloupe.overlay

import codeloupe.index.StoreUpdate

/** What a check found: the update that brings the overlay in line, and the worktree state to remember once it is applied. */
internal data class OverlayChange(
    val update: StoreUpdate,
    val entries: Map<String, Stamp>,
    val scan: Map<String, Stamp>,
    val prune: Set<String>,
    val ignored: Set<String>,
    /** Stamps of the `.gitignore` files [prune] and [ignored] were worked out under. */
    val ignoreFiles: Map<String, Stamp>,
    /** [ScanSnapshot.gitState] when git was asked; null when this check took it over from the last one. */
    val gitState: Map<String, Stamp>? = null,
)
