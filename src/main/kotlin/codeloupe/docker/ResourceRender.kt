package codeloupe.docker

/** The inventory as plain text: owned and adopted resources per workspace, unowned ones as a short list that is only reported. */
object ResourceRender {
    fun text(report: ResourceReport, classes: Set<OwnershipClass> = OwnershipClass.entries.toSet()): String = buildString {
        report.engine?.let { append(it).append('\n') }
        append("  ").append(OwnershipClass.entries.joinToString(" · ") { c -> "${report.resources.count { it.ownership == c }} ${c.name.lowercase()}" }).append('\n')
        for (c in OwnershipClass.entries.filter { it in classes }) {
            val rows = report.resources.filter { it.ownership == c }.map { row(it, c) }
            if (rows.isEmpty()) continue
            append('\n').append(c.name.lowercase()).append(if (c == OwnershipClass.UNOWNED) "  (reported only, never touched)" else "").append('\n')
            val widths = (0 until COLUMNS).map { i -> rows.maxOf { it[i].length } }
            for (row in rows) append("  ").append(row.indices.joinToString("  ") { row[it].padEnd(widths[it]) }.trimEnd()).append('\n')
        }
        for (problem in report.problems) append("! ").append(problem).append('\n')
    }

    private const val COLUMNS = 5

    // workspace (registry state) · kind · name · via · container state / created date.
    private fun row(r: ResourceEntry, c: OwnershipClass): List<String> = listOf(
        if (c == OwnershipClass.UNOWNED) "" else listOfNotNull(r.workspace, r.workspaceState?.let { "(${it.name.lowercase()})" } ?: "(not in registry)").joinToString(" "),
        r.kind.name.lowercase(),
        r.names.joinToString(", ").ifEmpty { r.id },
        r.via.orEmpty(),
        r.state ?: r.created?.take(10).orEmpty(),
    )
}
