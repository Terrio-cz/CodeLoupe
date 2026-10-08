package codeloupe.hooks

import kotlinx.serialization.Serializable

/**
 * How sessions orient themselves in their first turns (`ls`, `find`, `tree`, `Glob`), with and without the context the
 * session-start hook adds. Counts only.
 */
@Serializable
data class OrientationReport(val turns: Int, val withHook: Group, val withoutHook: Group) {
    /** [calls] are orientation calls in the first [OrientationReport.turns] turns of [sessions] sessions; [withAny] sessions made at least one. */
    @Serializable
    data class Group(val sessions: Int = 0, val calls: Int = 0, val withAny: Int = 0, val byKind: Map<String, Int> = emptyMap()) {
        val perSession: Double get() = if (sessions == 0) 0.0 else calls.toDouble() / sessions
    }

    fun render(): String = buildString {
        appendLine("orientation commands (ls, find, tree, Glob) in the first $turns turns of a session")
        fun line(name: String, g: Group) = appendLine(
            "  $name: ${g.sessions} sessions, ${g.calls} calls (%.2f per session), ${g.withAny} sessions with at least one".format(g.perSession) +
                if (g.byKind.isEmpty()) "" else "; " + g.byKind.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" },
        )
        line("without the hook's context", withoutHook)
        line("with the hook's context   ", withHook)
    }.trimEnd()
}
