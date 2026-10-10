package codeloupe.taskcode

import codeloupe.tracker.Section
import codeloupe.tracker.read.IssueRender

/**
 * The description of an issue as a planner starts from it: every section but the checklist (the `issue` section already
 * shows the criteria), each capped at a line boundary, the whole capped. What is cut says how to read it in full.
 */
object IssueDescription {
    fun render(description: String, perSection: Int = SECTION, total: Int = TOTAL): String {
        var left = total
        val parts = ArrayList<String>()
        for (section in Section.parse(description)) {
            if (IssueRender.onlyCriteria(section) || section.text.isBlank()) continue
            val title = section.title.ifEmpty { "intro" }
            if (left <= MIN_ROOM) {
                parts += "### $title (${section.text.length} chars; issue sections=[\"$title\"])"
                continue
            }
            val shown = cut(section.text.trim(), minOf(perSection, left), title)
            left -= shown.length
            parts += "### $title\n$shown"
        }
        return parts.joinToString("\n")
    }

    private fun cut(text: String, cap: Int, title: String): String {
        if (text.length <= cap) return text
        val at = text.lastIndexOf('\n', cap).takeIf { it > cap / 2 } ?: cap
        return text.take(at).trimEnd() + "\n… +${text.length - at} chars (issue sections=[\"$title\"])"
    }

    private const val SECTION = 1500
    private const val TOTAL = 5000
    private const val MIN_ROOM = 200
}
