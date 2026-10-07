package codeloupe.tracker.read

import codeloupe.tracker.Criterion
import codeloupe.tracker.Section
import codeloupe.tracker.Times
import codeloupe.tracker.TrackerIssue

/**
 * What changed in the parts a reader asked for between an earlier version of an issue and the current one;
 * comments, attachments and history count from [after], the time the reader's view was current.
 */
object IssueDelta {
    fun render(before: TrackerIssue, after: Long, c: IssueContext, parts: Parts, since: String): String {
        val now = c.issue
        val lines = buildList {
            if (before.summary != now.summary) add("summary: ${now.summary}")
            if (parts.brief || parts.shows("fields")) addAll(fields(before, now))
            if (parts.brief || parts.shows("links")) addAll(links(before, now))
            if (parts.brief || parts.shows("criteria")) addAll(criteria(before, now))
            addAll(sections(before, now, parts))
            addAll(comments(c, after, parts))
            val attached = c.attachments.filter { it.created > after }
            if (attached.isNotEmpty()) {
                if (parts.full || parts.shows("attachments")) attached.forEach { add("+ attachment ${IssueRender.attachment(it)}") }
                else add("+${attached.size} attachments")
            }
            if (parts.shows("history")) c.changes.filter { it.at > after }.forEach { add("${Times.short(it.at)} ${it.field}: ${it.removed} → ${it.added}") }
        }
        val head = "${now.id} changed since $since (updated ${Times.short(now.updated)})"
        return if (lines.isEmpty()) "$head; nothing changed in what you read" else "$head:\n" + lines.joinToString("\n")
    }

    private fun fields(before: TrackerIssue, now: TrackerIssue): List<String> {
        val old = IssueRender.allFields(before).toMap()
        val new = IssueRender.allFields(now).toMap()
        return (old.keys + new.keys).filter { old[it] != new[it] }.map { "$it: ${old[it] ?: "-"} → ${new[it] ?: "-"}" }
    }

    private fun links(before: TrackerIssue, now: TrackerIssue): List<String> {
        val old = before.links.map { "${it.verb} ${it.other}" }.toSet()
        val new = now.links.map { "${it.verb} ${it.other}" }.toSet()
        return (new - old).map { "+ $it" } + (old - new).map { "- $it" }
    }

    /** Ticks, new and removed criteria; the same text twice counts as two criteria. */
    private fun criteria(before: TrackerIssue, now: TrackerIssue): List<String> {
        val old = keyed(Criterion.parse(before.description))
        val new = keyed(Criterion.parse(now.description))
        return new.mapNotNull { (key, c) ->
            val was = old[key]
            when {
                was == null -> "+ ${IssueRender.criterion(c)}"
                was.done != c.done -> IssueRender.criterion(c)
                else -> null
            }
        } + (old.keys - new.keys).map { "removed criterion: ${old.getValue(it).text}" }
    }

    private fun keyed(criteria: List<Criterion>): Map<Pair<String, Int>, Criterion> {
        val seen = HashMap<String, Int>()
        return criteria.associateBy { c -> c.text to seen.merge(c.text, 1, Int::plus)!! }
    }

    /** Changed description sections: their names in a brief, their new text when they were asked for. */
    private fun sections(before: TrackerIssue, now: TrackerIssue, parts: Parts): List<String> {
        val old = Section.parse(before.description).associate { it.title to it.text }
        val current = Section.parse(now.description)
        val changed = current.filterNot(IssueRender::onlyCriteria).filter { withoutCriteria(old[it.title]) != withoutCriteria(it.text) }
        val removed = old.keys - current.map { it.title }.toSet()
        val shown = changed.filter { parts.shows(title(it.title)) }
        return buildList {
            shown.forEach { add("## ${title(it.title)}\n${it.text}") }
            val named = (changed - shown.toSet()).map { title(it.title) }
            if (named.isNotEmpty() && (parts.brief || parts.full)) add("changed sections: ${named.joinToString(", ")}")
            removed.filter { parts.brief || parts.shows(title(it)) }.forEach { add("- section ${title(it)}") }
        }
    }

    private fun title(title: String) = title.ifEmpty { "intro" }

    /** Criteria have their own diff; a section is changed only when something besides its ticks changed. */
    private fun withoutCriteria(text: String?): String? = text?.lines()?.filter { Criterion.parse(it).isEmpty() }?.joinToString("\n")?.trim()

    private fun comments(c: IssueContext, after: Long, parts: Parts): List<String> {
        val added = c.comments.filter { it.created > after }
        val edited = c.comments.filter { it.created <= after && (it.updated ?: 0) > after }
        if (added.isEmpty() && edited.isEmpty()) return emptyList()
        if (!parts.full && !parts.shows("comments")) {
            val authors = (added + edited).mapNotNull { it.author }.distinct().joinToString(", ")
            return listOf(listOfNotNull(added.size.takeIf { it > 0 }?.let { "+$it comments" }, edited.size.takeIf { it > 0 }?.let { "$it edited" }).joinToString(", ") + " ($authors)")
        }
        return added.map { "+ ${IssueRender.comment(it)}" } + edited.map { "~ ${IssueRender.comment(it)}" }
    }
}
