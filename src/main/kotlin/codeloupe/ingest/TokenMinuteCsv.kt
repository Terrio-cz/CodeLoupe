package codeloupe.ingest

/** [TokenMinute]s as CSV: a header, then a row per minute, run and model, empty cells for what is not known. */
object TokenMinuteCsv {
    private val HEADER = listOf("minute", "session", "kind", "role", "project", "model", "input", "cw5m", "cw1h", "cacheRead", "output", "thinking", "account", "identity")

    fun render(rows: List<TokenMinute>): String = buildString {
        append(HEADER.joinToString(",")).append('\n')
        for (r in rows) {
            val cells = listOf(r.minute, r.session, r.kind, r.role, r.project, r.model, r.input.toString(), r.cw5m.toString(), r.cw1h.toString(), r.cacheRead.toString(),
                r.output.toString(), r.thinking.toString(), r.account, r.identity)
            append(cells.joinToString(",") { LimitCsv.cell(it) }).append('\n')
        }
    }
}
