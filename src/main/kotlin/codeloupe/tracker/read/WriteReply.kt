package codeloupe.tracker.read

import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.mirror.WriteResult

/** The answer to a write: what changed (field, old → new), the comment added, and the state the issue is in now. One line. */
object WriteReply {
    const val LIMIT = 300
    private const val VALUE = 40
    private const val PROBLEM = 120

    /** [requested] names the fields the caller set; when the mirror held no earlier version they are all that can be shown. */
    fun render(result: WriteResult, requested: Collection<String>): String {
        val after = result.after
        val parts = changes(result, requested).toMutableList()
        result.comment?.let { parts += "+comment ${it.id}" }
        result.problem?.let { parts += it.oneLine(PROBLEM) }
        val tail = if (parts.any { it.startsWith("State:") }) "" else " · now ${after.state ?: "-"}"
        if (parts.isEmpty()) return "${after.id} no change$tail"
        fun line(n: Int) = "${after.id} " + parts.take(n).joinToString(" · ") + (if (n < parts.size) " · … +${parts.size - n}" else "") + tail
        var n = parts.size
        while (n > 1 && line(n).length > LIMIT) n--
        return line(n).take(LIMIT)
    }

    private fun changes(result: WriteResult, requested: Collection<String>): List<String> {
        val before = result.before
        val now = values(result.after)
        val asked = requested.map { it.lowercase() }.toSet()
        val old = before?.let(::values)
        val out = ArrayList<String>()
        for ((key, shown) in now.entries) {
            val (name, value) = shown
            val prior = old?.get(key)?.second
            if (old != null && prior == value) continue
            if (old == null && key !in asked) continue
            out += "$name: ${prior?.oneLine(VALUE) ?: "—"}→${value.oneLine(VALUE)}"
        }
        // A cleared field is gone from the new version.
        old?.forEach { (key, shown) -> if (key !in now) out += "${shown.first}: ${shown.second.oneLine(VALUE)}→—" }
        if (descriptionChanged(before, result.after, asked)) out += "description changed (${result.after.description.length} chars)"
        return out
    }

    private fun descriptionChanged(before: TrackerIssue?, after: TrackerIssue, asked: Set<String>) =
        if (before == null) "description" in asked else before.description != after.description

    /** Lower-case field name → name and value, for the planning fields and the custom ones. */
    private fun values(issue: TrackerIssue): Map<String, Pair<String, String>> = buildMap {
        put("summary", "Summary" to issue.summary)
        issue.state?.let { put("state", "State" to it) }
        issue.type?.let { put("type", "Type" to it) }
        issue.priority?.let { put("priority", "Priority" to it) }
        issue.assignee?.let { put("assignee", "Assignee" to it) }
        issue.fields.forEach { put(it.name.lowercase(), it.name to it.value) }
    }

    private fun String.oneLine(max: Int): String {
        val flat = replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }
}
