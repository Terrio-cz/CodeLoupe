package codeloupe.reconcile

/** The plan and the result of a run as plain text. */
object ReconcileRender {
    fun plan(plan: ReconcilePlan): String = buildString {
        append(if (plan.auto) "auto cleanup is on" else "auto cleanup is off (workspaces.reconcile.auto); nothing runs unless asked").append('\n')
        append("  ").append(Verdict.entries.joinToString(" · ") { "${plan.counts[it.name.lowercase()] ?: 0} ${it.name.lowercase()}" }).append('\n')
        if (plan.planHash.isNotEmpty()) append("  plan ").append(plan.planHash).append(" (pass it as --plan to confirm what is listed here)\n")
        for (verdict in listOf(Verdict.AUTO, Verdict.CONFIRM, Verdict.PROTECTED, Verdict.KEEP)) {
            val rows = plan.entries.filter { it.verdict == verdict }.map { row(it) }
            if (rows.isEmpty()) continue
            append('\n').append(verdict.name.lowercase()).append(heading(verdict)).append('\n')
            val widths = (0 until COLUMNS).map { i -> rows.maxOf { it[i].length } }
            for (row in rows) append("  ").append(row.indices.joinToString("  ") { row[it].padEnd(widths[it]) }.trimEnd()).append('\n')
        }
        for (problem in plan.problems) append("! ").append(problem).append('\n')
    }

    fun run(run: ReconcileRun): String = buildString {
        if (run.actions.isEmpty()) append("nothing to do\n")
        for (a in run.actions) {
            append(a.outcome.name.lowercase().padEnd(8)).append(a.kind.name.lowercase().padEnd(10)).append(a.name)
            a.workspace?.let { append("  (").append(it).append(')') }
            if (a.detail.isNotEmpty()) append("  ").append(a.detail)
            append('\n')
        }
        append('\n').append(plan(run.remaining))
    }

    private fun heading(verdict: Verdict) = when (verdict) {
        Verdict.AUTO -> "  (cleaned by itself: labelled, workspace landed)"
        Verdict.CONFIRM -> "  (name it with --confirm <key> or --workspace <name>)"
        Verdict.PROTECTED -> "  (never touched)"
        Verdict.KEEP -> "  (stays)"
    }

    private const val COLUMNS = 4

    // workspace · kind · name (key for a volume is its name) · why, with the retry state when there is one.
    private fun row(e: PlanEntry): List<String> = listOf(
        e.workspace.orEmpty(),
        e.kind.name.lowercase(),
        e.key,
        e.reason + if (e.attempts > 0) "; ${e.attempts} failed attempts, next ${e.nextAttempt?.take(16)}, last: ${e.lastError}" else "",
    )
}
