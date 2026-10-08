package codeloupe.workspace

/** The registry as plain text, one block per repository. */
object WorkspaceRender {
    fun text(list: WorkspaceList, states: Set<WorkspaceState> = WorkspaceState.entries.toSet()): String = buildString {
        for (repo in list.repos) {
            if (isNotEmpty()) append('\n')
            append(repo.name).append("  ").append(repo.repo).append("  default ").append(repo.defaultRef).append('\n')
            append("  ").append(repo.counts.entries.joinToString(" · ") { "${it.value} ${it.key}" }).append('\n')
            val rows = repo.workspaces.filter { it.state in states }.map(::row)
            val widths = (0 until COLUMNS).map { c -> rows.maxOfOrNull { it[c].length } ?: 0 }
            for (row in rows) {
                append("  ").append(row.indices.joinToString("  ") { row[it].padEnd(widths[it]) }.trimEnd()).append('\n')
            }
        }
        for (problem in list.problems) append("! ").append(problem).append('\n')
        if (isEmpty()) append("no repositories; list them in config.json under workspaces.repos\n")
    }

    private const val COLUMNS = 7

    // state · name · task (tracker state) · branch · ahead · activity · size, then the note as a last column.
    private fun row(w: Workspace): List<String> = listOf(
        w.state.name.lowercase(),
        w.name,
        listOfNotNull(w.taskId, w.tracker?.state?.let { "($it)" }).joinToString(" "),
        w.branch ?: w.head?.take(8)?.let { "($it)" } ?: "",
        w.merge?.let { if (it.merged) "merged" else "+${it.ahead}" } ?: "",
        w.lastActivity?.take(10).orEmpty(),
        listOfNotNull(w.sizeBytes?.let(::megabytes), w.note).joinToString("  "),
    )

    private fun megabytes(bytes: Long) = "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_048_576.0)
}
