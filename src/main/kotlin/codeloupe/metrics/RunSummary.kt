package codeloupe.metrics

import kotlinx.serialization.Serializable

/** What a report keeps of one run; field names are those of the workspace's `run/codemetrics.mjs`, so reports compare. */
@Serializable
data class RunSummary(
    val file: String,
    val kind: String,
    val role: String,
    val ter: String?,
    val model: String?,
    val start: String?,
    val wallSec: Long,
    val turns: Int,
    val usage: Usage,
    val cost: Long,
    val peakContext: Long,
    val toolSec: Long,
    val byCat: Map<String, CatStats>,
    val cmds: Map<String, CmdStats>,
    val codeRead: CodeReads,
    val codeEdit: CodeEdits,
    val toolErrors: Int,
    val startCtx: StartCtx? = null,
) {
    companion object {
        fun of(run: Run): RunSummary {
            val byCat = LinkedHashMap<String, CatStats>()
            val cmds = LinkedHashMap<String, CmdStats>()
            for (t in run.tools) {
                byCat.merge(t.category, CatStats(1, t.chars.toLong(), t.carried, t.attr, if (t.err) 1 else 0, t.ms), CatStats::plus)
                if (t.cmd != null) cmds.merge("${t.category} | ${t.cmd}", CmdStats(1, t.attr)) { a, b -> CmdStats(a.calls + b.calls, a.attr + b.attr) }
            }
            val reads = run.tools.filter { it.category == "code_read" }
            val perFile = reads.groupingBy { it.file }.eachCount()
            val edits = run.tools.filter { it.category == "code_write" }
            return RunSummary(
                file = run.file, kind = run.kind, role = run.role, ter = run.ter, model = run.model, start = run.start, wallSec = run.wallSec,
                turns = run.turns, usage = run.usage, cost = run.usage.cost(), peakContext = run.peakContext,
                toolSec = Math.round(run.tools.sumOf { it.ms } / 1000.0), byCat = byCat, cmds = cmds,
                codeRead = CodeReads(reads.size, perFile.size, perFile.values.sumOf { it - 1 }, reads.count { !it.partial }, reads.sumOf { it.chars.toLong() }),
                codeEdit = CodeEdits(edits.size, edits.count { it.err }, edits.filter { it.err }.take(SAMPLES).map { it.errText }),
                toolErrors = run.tools.count { it.err }, startCtx = run.startCtx,
            )
        }

        private const val SAMPLES = 3
    }
}
