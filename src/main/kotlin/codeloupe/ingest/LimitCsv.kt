package codeloupe.ingest

/** [LimitSnapshot]s as CSV for a spreadsheet: a header, then a row per snapshot, empty cells for what is not known. */
object LimitCsv {
    private val HEADER = listOf("at", "kind", "group", "scope", "percent", "resetsAt", "severity", "active", "session", "account", "identity")

    fun render(rows: List<LimitSnapshot>): String = buildString {
        append(HEADER.joinToString(",")).append('\n')
        for (r in rows) {
            append(listOf(r.at, r.kind, r.group, r.scope, r.percent?.toString(), r.resetsAt, r.severity, r.active?.toString(), r.session, r.account, r.identity).joinToString(",") { cell(it) }).append('\n')
        }
    }

    private fun cell(value: String?): String = when {
        value == null -> ""
        value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + value.replace("\"", "\"\"") + "\""
        else -> value
    }
}
