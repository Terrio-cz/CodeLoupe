package codeloupe.uiapi

import kotlinx.serialization.Serializable

/**
 * The notes the brains hold on one task, one [Notes] per configured brain that has a file for it; empty when none has. Read from the task files,
 * never waiting on a brain index: [Notes.indexing] says the superseded marks are not known yet.
 */
@Serializable
data class TaskBrain(val brains: List<Notes> = emptyList()) {
    @Serializable
    data class Notes(
        val workspaceId: String,
        val workspace: String,
        /** The state keys (`plan`, `code`, `rev`, `test`, `round`, `land`, `merge`, `close`, `hold` ...) as the `%` lines left them. */
        val state: List<StateKey>,
        /** Newest first, at most [MAX_ENTRIES]. */
        val entries: List<Entry>,
        val entryCount: Int,
        val lastAt: String?,
        /** True while the brain index is not built: no entry is marked superseded yet. */
        val indexing: Boolean = false,
        /** Set when the file could not be read; the other fields are then empty. */
        val error: String? = null,
    )

    @Serializable
    data class StateKey(val key: String, val value: String)

    @Serializable
    data class Entry(
        /** The notation's kind: `=` evidence, `~` decision, `!P2` blocker, `?` unknown, `+` fact, `>` action, `->` handoff, `%` state, `@` context, `x` rejected; empty without one. */
        val marker: String,
        val text: String,
        val who: String,
        val at: String,
        val line: Int,
        /** Declined on purpose (`brain.mjs reject`, marker `x`). */
        val rejected: Boolean = false,
        /** An automated check landed for what the entry says; it no longer counts in briefs and risk. */
        val superseded: Boolean = false,
        val coveredBy: String? = null,
    )

    companion object {
        const val MAX_ENTRIES = 300
        const val MAX_TEXT = 2_000
    }
}
