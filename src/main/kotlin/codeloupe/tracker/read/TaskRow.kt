package codeloupe.tracker.read

/** The one-line view of a task: what lists, graphs and link lines show. */
data class TaskRow(
    val id: String,
    val summary: String,
    val state: String?,
    val resolved: Boolean,
    val type: String?,
    val priority: String?,
    val priorityRank: Int?,
    val parent: String?,
    val updated: Long,
    /** Unresolved `depends on` targets; a `?` suffix marks one the mirror does not hold. */
    val blockers: List<String>,
) {
    /** `TER-5 In Progress · Bug · Major ‹TER-1› Summary ⛔TER-3` */
    fun line(withParent: Boolean = true): String = buildString {
        append(id).append(' ').append(listOfNotNull(state ?: if (resolved) "resolved" else "open", type, priority).joinToString(" · "))
        if (withParent && parent != null) append(" ‹").append(parent).append('›')
        append(' ').append(shorten(summary, SUMMARY))
        if (blockers.isNotEmpty()) append(" ⛔").append(blockers.joinToString(","))
    }

    private fun shorten(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    private companion object {
        const val SUMMARY = 90
    }
}
