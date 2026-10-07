package codeloupe.tracker.read

import codeloupe.tracker.IssueAttachment
import codeloupe.tracker.IssueComment
import codeloupe.tracker.IssueLink
import codeloupe.tracker.LinkKind
import codeloupe.tracker.Times
import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.Criterion
import codeloupe.tracker.Section

/** Compact markdown of an issue: the brief, the full issue, or named sections. */
object IssueRender {
    fun render(c: IssueContext, parts: Parts): String = when {
        parts.full -> full(c)
        parts.brief -> brief(c)
        else -> sections(c, parts.sections)
    }

    private fun brief(c: IssueContext): String = buildList {
        add(header(c.issue))
        add(fieldsLine(c.issue))
        addAll(linkLines(c))
        val criteria = Criterion.parse(c.issue.description)
        if (criteria.isNotEmpty()) {
            add("Criteria ${criteria.count { it.done }}/${criteria.size}:")
            criteria.forEach { add(criterion(it)) }
        }
        val index = Section.parse(c.issue.description).filterNot(::onlyCriteria).joinToString(" · ") { "${it.title.ifEmpty { "intro" }} ${it.lines}" }
        if (index.isNotEmpty()) add("Sections (lines): $index")
        add(footer(c))
    }.joinToString("\n")

    private fun full(c: IssueContext): String = buildList {
        add(header(c.issue))
        add(fieldsLine(c.issue))
        addAll(linkLines(c))
        if (c.issue.description.isNotBlank()) add("\n" + c.issue.description.trim() + "\n")
        if (c.comments.isNotEmpty()) add("## Comments\n" + c.comments.joinToString("\n") { comment(it) })
        if (c.attachments.isNotEmpty()) add("## Attachments\n" + c.attachments.joinToString("\n") { attachment(it) })
        add("updated ${Times.short(c.issue.updated)}")
    }.joinToString("\n")

    private fun sections(c: IssueContext, names: Set<String>): String {
        val described = Section.parse(c.issue.description)
        val out = mutableListOf("${c.issue.id} [${c.issue.state ?: "-"}] ${c.issue.summary}")
        for (name in names) {
            val part = pseudo(c, name) ?: described.filter { Parts.matches(name, it.title.ifEmpty { "intro" }) }
                .takeIf { it.isNotEmpty() }?.joinToString("\n") { "## ${it.title.ifEmpty { "intro" }}\n${it.text}" }
            out += part ?: "no section '$name'; have: ${(described.map { it.title.ifEmpty { "intro" } } + Parts.PSEUDO).joinToString(", ")}"
        }
        return out.joinToString("\n")
    }

    private fun pseudo(c: IssueContext, name: String): String? = when (name) {
        "criteria" -> "## Criteria\n" + Criterion.parse(c.issue.description).joinToString("\n") { criterion(it) }.ifEmpty { "(none)" }
        "fields" -> "## Fields\n" + allFields(c.issue).joinToString("\n") { "${it.first}: ${it.second}" }
        "links" -> "## Links\n" + c.issue.links.joinToString("\n") { link(c, it) }.ifEmpty { "(none)" }
        "comments" -> "## Comments\n" + c.comments.joinToString("\n") { comment(it) }.ifEmpty { "(none)" }
        "attachments" -> "## Attachments\n" + c.attachments.joinToString("\n") { attachment(it) }.ifEmpty { "(none)" }
        "history" -> "## History\n" + c.changes.joinToString("\n") { "${Times.short(it.at)} ${it.field}: ${it.removed.ifEmpty { "-" }} → ${it.added.ifEmpty { "-" }}${it.author?.let { a -> " ($a)" }.orEmpty()}" }
            .ifEmpty { "(none)" }
        else -> null
    }

    fun header(issue: TrackerIssue) = "${issue.id} ${issue.summary}"

    /** `In Progress · Bug · Major · @login · Subsystem: Api`; long values only by name and size. */
    fun fieldsLine(issue: TrackerIssue): String =
        (listOfNotNull(issue.state, issue.type, issue.priority, issue.assignee?.let { "@$it" }) + issue.fields.map { f ->
            if (f.value.length > LONG_FIELD) "${f.name} (${f.value.length} chars)" else "${f.name}: ${f.value}"
        }).joinToString(" · ")

    /** Every field by name, the planning ones first: for `sections=[fields]` and deltas. */
    fun allFields(issue: TrackerIssue): List<Pair<String, String>> =
        listOfNotNull(issue.state?.let { "State" to it }, issue.type?.let { "Type" to it }, issue.priority?.let { "Priority" to it }, issue.assignee?.let { "Assignee" to it }) +
            issue.fields.map { it.name to it.value }

    /** One line per kind of link: the epic with its title, blockers with their state, the rest as ids. */
    fun linkLines(c: IssueContext): List<String> {
        val byKind = c.issue.links.groupBy { it.kind }
        return buildList {
            byKind[LinkKind.PARENT]?.forEach { add("epic ${it.other} ${c.linkedRow(it.other)?.summary.orEmpty()}".trimEnd()) }
            byKind[LinkKind.SUBTASK]?.let { subtasks ->
                val rows = subtasks.mapNotNull { c.linkedRow(it.other) }
                add("subtasks ${subtasks.size}: ${rows.count { it.resolved }} resolved")
            }
            byKind[LinkKind.DEPENDS_ON]?.let { links ->
                add("depends on " + links.joinToString(", ") { l -> "${l.other} ${c.linkedRow(l.other)?.let { it.state ?: if (it.resolved) "resolved" else "open" } ?: "(not mirrored)"}" })
            }
            for ((kind, links) in byKind) {
                if (kind in setOf(LinkKind.PARENT, LinkKind.SUBTASK, LinkKind.DEPENDS_ON)) continue
                links.groupBy { it.verb }.forEach { (verb, same) -> add("$verb ${ids(same.map { it.other })}") }
            }
        }
    }

    private fun ids(ids: List<String>) = if (ids.size <= MAX_IDS) ids.joinToString(", ") else ids.take(MAX_IDS).joinToString(", ") + " (+${ids.size - MAX_IDS})"

    private fun link(c: IssueContext, l: IssueLink): String = "${l.verb} " + (c.linkedRow(l.other)?.line(withParent = false) ?: "${l.other} (not mirrored)")

    fun criterion(it: Criterion) = "[${if (it.done) "x" else " "}] ${it.text}"

    fun comment(it: IssueComment) = "${it.author ?: "?"} ${Times.short(it.created)}: ${it.text.trim()}"

    fun attachment(it: IssueAttachment) = "${it.name} (${size(it.size)}, ${it.author ?: "?"} ${Times.short(it.created)})"

    private fun footer(c: IssueContext): String {
        val (count, last) = c.lastComment
        return listOfNotNull(
            if (last == null) "no comments" else "$count comments (last ${last.author ?: "?"} ${Times.short(last.created)})",
            c.attachments.size.takeIf { it > 0 }?.let { "$it attachments" },
            "updated ${Times.short(c.issue.updated)}",
        ).joinToString(" · ")
    }

    /** A section that holds nothing but checklist lines is already shown as the criteria. */
    fun onlyCriteria(section: Section): Boolean {
        val lines = section.text.lines().filter { it.isNotBlank() }
        return lines.isNotEmpty() && lines.size == Criterion.parse(section.text).size
    }

    private fun size(bytes: Long) = if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"

    private const val LONG_FIELD = 60
    private const val MAX_IDS = 6
}
