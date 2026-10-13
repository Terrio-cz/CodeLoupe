package codeloupe.tracker

/**
 * Edits one `- [ ]` / `- [x]` line of an issue description. A criterion is found by a case-insensitive substring of its text and
 * must be the only one that matches: a missing or ambiguous match is a [TrackerException] and nothing is written. Everything but
 * the one line (other lines, line endings, the box style) stays as it was.
 */
object CriterionEdit {
    private val LINE = Regex("^(\\s*[-*+] \\[)([ xX])(] )(.+)$")

    /** The description with the edit applied and the criterion's text as it reads now. */
    data class Edited(val description: String, val text: String)

    /** Ticks the one open criterion containing [match]. */
    fun tick(description: String, match: String): Edited {
        val lines = description.split('\n').toMutableList()
        val hit = unique(lines, match, onlyOpen = true)
        val parsed = LINE.find(lines[hit])!!
        lines[hit] = lines[hit].replaceRange(parsed.groups[2]!!.range, "x")
        return Edited(lines.joinToString("\n"), parsed.groupValues[4].trim())
    }

    /** Replaces the text of the one criterion (open or ticked) containing [match] by [to]; its box stays as it is. */
    fun rewrite(description: String, match: String, to: String): Edited {
        val text = to.trim()
        if (text.isEmpty()) throw TrackerException("--to is empty: a criterion needs text")
        if ('\n' in text || '\r' in text) throw TrackerException("--to is one line: a criterion is one line of the description")
        val lines = description.split('\n').toMutableList()
        val hit = unique(lines, match, onlyOpen = false)
        val parsed = LINE.find(lines[hit])!!
        lines[hit] = lines[hit].replaceRange(parsed.groups[4]!!.range, text)
        return Edited(lines.joinToString("\n"), text)
    }

    private fun unique(lines: List<String>, match: String, onlyOpen: Boolean): Int {
        val needle = match.trim()
        if (needle.isEmpty()) throw TrackerException("the criterion text to match is empty")
        val criteria = lines.mapIndexedNotNull { i, line -> LINE.find(line)?.let { i to it } }
        val found = criteria.filter { (_, m) -> m.groupValues[4].contains(needle, ignoreCase = true) }
        val open = found.filter { (_, m) -> m.groupValues[2] == " " }
        val candidates = if (onlyOpen) open else found
        return when {
            candidates.size == 1 -> candidates.single().first
            candidates.isEmpty() && onlyOpen && found.isNotEmpty() -> throw TrackerException("already ticked: ${shown(found.first().second)}")
            candidates.isEmpty() -> throw TrackerException("no ${if (onlyOpen) "open " else ""}criterion contains '${needle.take(60)}'")
            else -> throw TrackerException(
                "'${needle.take(60)}' matches ${candidates.size} ${if (onlyOpen) "open " else ""}criteria, use more of the text: " +
                    candidates.take(4).joinToString("; ") { (_, m) -> shown(m) } + if (candidates.size > 4) "; …" else "",
            )
        }
    }

    private fun shown(m: MatchResult): String = m.groupValues[4].trim().let { if (it.length <= 60) it else it.take(59) + "…" }
}
