package codeloupe.tracker.read

import codeloupe.tracker.LinkKind
import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.mirror.MirrorStore

/**
 * `task_graph(id, depth)`: the task, then its links one line each, indented by depth. Below the first level a chain
 * goes on in its own direction only (what a dependency depends on, what a dependant is required for, subtasks of
 * subtasks); the epic is listed, not expanded, and every task appears once.
 */
object TaskGraph {
    private val ORDER = listOf(LinkKind.PARENT, LinkKind.DEPENDS_ON, LinkKind.REQUIRED_FOR, LinkKind.SUBTASK, LinkKind.DUPLICATES, LinkKind.DUPLICATED_BY, LinkKind.RELATES, LinkKind.OTHER)
    private val DEEP = setOf(LinkKind.DEPENDS_ON, LinkKind.REQUIRED_FOR, LinkKind.SUBTASK)

    fun render(store: MirrorStore, id: String, depth: Int, limit: Int): String {
        val root = store.issue(id) ?: return "no issue $id"
        val lines = mutableListOf(TaskRows.row(store, root.id)!!.line())
        val seen = mutableSetOf(root.id.uppercase())
        var omitted = 0

        fun expand(issue: TrackerIssue, level: Int, follow: LinkKind?) {
            val links = issue.links.filter { follow == null || it.kind == follow }.sortedBy { ORDER.indexOf(it.kind) }
            val rows = TaskRows.byIds(store, links.map { it.other })
            for (link in links) {
                if (!seen.add(link.other.uppercase())) continue
                if (lines.size > limit) {
                    omitted++
                    continue
                }
                val row = rows[link.other.uppercase()]
                lines += "  ".repeat(level) + link.verb + " " + (row?.line(withParent = link.kind != LinkKind.SUBTASK) ?: "${link.other} (not mirrored)")
                if (level < depth && link.kind in DEEP) store.issue(link.other)?.let { expand(it, level + 1, link.kind) }
            }
        }
        expand(root, 1, null)
        if (omitted > 0) lines += "… +$omitted more (limit)"
        return lines.joinToString("\n")
    }
}
