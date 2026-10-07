package codeloupe.overlay

import codeloupe.index.StoreUpdate

/** What a check found: the update that brings the overlay in line, and the worktree state to remember once it is applied. */
internal data class OverlayChange(
    val update: StoreUpdate,
    val entries: Map<String, Stamp>,
    val scan: Map<String, Stamp>,
    val prune: Set<String>,
    val ignored: Set<String>,
)
