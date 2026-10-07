package codeloupe.tracker.read

import codeloupe.tracker.mirror.MirrorStore

/**
 * `ready(scope)`: open tasks without subtasks whose dependencies are all resolved and that no worktree holds,
 * most urgent first; then one line each for what is blocked and what is taken. A dependency outside the mirror
 * counts as open (`?`): nothing proves it done.
 */
object ReadyTasks {
    private const val LEAF = "i.resolved IS NULL AND NOT EXISTS (SELECT 1 FROM issues s WHERE s.parent = i.id)"
    private const val LISTED = 8

    fun render(store: MirrorStore, filter: TaskFilter, held: Map<String, String>, limit: Int): String {
        val rows = TaskList.matching(store, filter, LEAF)
        val (blocked, free) = rows.partition { it.blockers.isNotEmpty() }
        val (taken, ready) = free.partition { it.id.uppercase() in held }
        val ordered = if (filter.order == TaskFilter.DEFAULT_ORDER) ready.sortedBy { it.priorityRank ?: Int.MAX_VALUE } else ready
        return buildList {
            add(TaskList.render(ordered, limit, "ready tasks"))
            if (blocked.isNotEmpty()) add("blocked ${blocked.size}: " + few(blocked.map { "${it.id} ⛔${it.blockers.joinToString(",")}" }))
            if (taken.isNotEmpty()) add("in worktrees ${taken.size}: " + few(taken.map { "${it.id} (${held[it.id.uppercase()]})" }))
        }.joinToString("\n")
    }

    private fun few(items: List<String>) = items.take(LISTED).joinToString(" · ") + if (items.size > LISTED) " · …" else ""
}
