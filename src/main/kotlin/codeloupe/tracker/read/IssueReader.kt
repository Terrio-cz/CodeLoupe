package codeloupe.tracker.read

import codeloupe.tracker.Criterion
import codeloupe.tracker.Times
import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.TrackerMirror

/**
 * Answers `issue`: refreshes the one issue if needed, then renders what was asked — or, for a reader who already
 * saw it from the same session key, "unchanged" or only what changed since (their read, or an explicit `since`).
 * Without a session key nothing is remembered.
 */
class IssueReader(private val memory: ReadMemory, private val clock: () -> Long = System::currentTimeMillis) {
    /** Drops what the readers [matching] were told, so their next read is whole again. */
    fun forget(matching: (String) -> Boolean) = memory.forget(matching)

    suspend fun read(mirror: TrackerMirror, id: String, parts: Parts, since: String?, session: String): String {
        val note = mirror.refresh(id)
        val issue = mirror.store.issue(id) ?: return note ?: "no issue $id"
        val previous = session.takeIf { it.isNotEmpty() }?.let { memory.get(it, issue.id) }
        val answer = answer(mirror, issue, parts, since?.trim()?.takeIf { it.isNotEmpty() }, previous)
        if (session.isNotEmpty()) {
            val seen = if (previous != null && previous.updated == issue.updated) parts.plus(previous.parts) else parts
            memory.put(session, issue.id, ReadMemory.Read(issue.updated, clock(), seen))
        }
        return if (note != null && note.startsWith("(")) "$answer\n$note" else answer
    }

    private fun answer(mirror: TrackerMirror, issue: TrackerIssue, parts: Parts, since: String?, previous: ReadMemory.Read?): String {
        val context by lazy { IssueContext(mirror.store, issue) }
        if (since == null || since == "last") {
            if (previous == null || !parts.within(previous.parts)) return IssueRender.render(context, parts)
            if (previous.updated == issue.updated) return unchanged(issue, previous.at)
            val before = mirror.store.revision(issue.id, previous.updated) ?: return IssueRender.render(context, parts)
            return IssueDelta.render(before, previous.updated, context, parts, "your read at ${Times.short(previous.at)}")
        }
        if (since == "none") return IssueRender.render(context, parts)
        val time = Times.parse(since) ?: throw IllegalArgumentException("since: an ISO time like 2026-10-07T10:12Z, 'last' or 'none'")
        if (issue.updated <= time) return "${issue.id} unchanged since ${Times.short(time)} (updated ${Times.short(issue.updated)})"
        val before = mirror.store.revision(issue.id, time)
            ?: return "(no version of ${issue.id} from ${Times.short(time)} in the mirror; the whole issue)\n" + IssueRender.render(context, parts)
        return IssueDelta.render(before, time, context, parts, Times.short(time))
    }

    /** One line, with state and checklist progress, so a reader that lost its context still knows where the issue stands. */
    private fun unchanged(issue: TrackerIssue, readAt: Long): String {
        val criteria = Criterion.parse(issue.description)
        val progress = if (criteria.isEmpty()) "" else ", criteria ${criteria.count { it.done }}/${criteria.size}"
        return "${issue.id} unchanged since your read at ${Times.short(readAt)} (${issue.state ?: "-"}$progress; since=none shows it again)"
    }
}
