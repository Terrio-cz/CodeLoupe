package codeloupe.brain

import kotlinx.serialization.Serializable

/**
 * `POST /brain/resolve`: turn `file:line` citations into anchors. [trusted] says the lines were read in [codeRoot] itself (the writer's own
 * worktree), so the declaration found there is the one meant; otherwise a `@sym:` is given only when the finding's text names that declaration or
 * its container, because lines drift.
 */
@Serializable
data class ResolveRequest(val workspace: String, val codeRoot: String? = null, val trusted: Boolean = false, val refs: List<LineRef> = emptyList())
