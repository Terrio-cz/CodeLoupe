package codeloupe.tracker.read

import codeloupe.tracker.FieldChange
import codeloupe.tracker.IssueAttachment
import codeloupe.tracker.IssueComment
import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.mirror.MirrorStore

/** An issue with what its answer may need from the mirror, each part loaded only when a renderer asks for it. */
class IssueContext(store: MirrorStore, val issue: TrackerIssue) {
    val comments: List<IssueComment> by lazy { store.comments(issue.id) }
    val lastComment: Pair<Int, IssueComment?> by lazy { store.lastComment(issue.id) }
    val attachments: List<IssueAttachment> by lazy { store.attachments(issue.id) }
    val changes: List<FieldChange> by lazy { store.changes(issue.id) }

    /** Linked tasks by upper-case id; links to issues outside the mirror are absent. */
    private val linked: Map<String, TaskRow> by lazy { TaskRows.byIds(store, issue.links.map { it.other }.toSet()) }

    fun linkedRow(id: String): TaskRow? = linked[id.uppercase()]
}
