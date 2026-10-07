package codeloupe.golden

import java.util.Locale

/** Totals and a Markdown table of a golden run; hits and oracle entries are `path:line`. */
class GoldenReport(private val symbols: List<Symbol>) {
    data class Symbol(
        val query: String,
        val kind: String,
        val rgPositions: Int,
        val covered: Int,
        val exact: Set<String>,
        val candidate: Set<String>,
        val oracle: Set<String>,
        val ms: Long,
    ) {
        val exactRight get() = exact.count { it in oracle }
        val found get() = oracle.count { it in exact || it in candidate }
    }

    val superset: Double = ratio(symbols.sumOf { it.covered }, symbols.sumOf { it.rgPositions })
    val precision: Double = ratio(symbols.sumOf { it.exactRight }, symbols.sumOf { it.exact.size })
    val recall: Double = ratio(symbols.sumOf { it.found }, symbols.sumOf { it.oracle.size })
    val exactRecall: Double = ratio(symbols.sumOf { it.exactRight }, symbols.sumOf { it.oracle.size })
    val candidateShare: Double = ratio(symbols.sumOf { it.candidate.size }, symbols.sumOf { it.exact.size + it.candidate.size })

    fun markdown(commit: String): String = buildString {
        val times = symbols.map { it.ms }.sorted()
        appendLine("# usages golden test — TerrioImporter ${commit.take(10)}")
        appendLine()
        appendLine("| metric | value |")
        appendLine("|---|---|")
        appendLine("| symbols | ${symbols.size} |")
        appendLine("| superset of `rg -w` code positions | ${percent(superset)} (${symbols.sumOf { it.covered }}/${symbols.sumOf { it.rgPositions }}) |")
        appendLine("| exact precision | ${percent(precision)} (${symbols.sumOf { it.exactRight }}/${symbols.sumOf { it.exact.size }}) |")
        appendLine("| oracle recall, exact + candidate | ${percent(recall)} (${symbols.sumOf { it.found }}/${symbols.sumOf { it.oracle.size }}) |")
        appendLine("| oracle recall, exact only | ${percent(exactRecall)} |")
        appendLine("| candidate share of listed lines | ${percent(candidateShare)} (${symbols.sumOf { it.candidate.size }}) |")
        appendLine("| query time p50 / max | ${times[times.size / 2]} ms / ${times.last()} ms |")
        appendLine()
        appendLine("| symbol | kind | rg | exact | candidate | oracle | exact ok | ms |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (s in symbols) {
            appendLine("| `${s.query}` | ${s.kind} | ${s.covered}/${s.rgPositions} | ${s.exact.size} | ${s.candidate.size} | ${s.oracle.size} | ${s.exactRight} | ${s.ms} |")
        }
        val wrong = symbols.flatMap { s -> s.exact.filter { it !in s.oracle }.map { "${s.query}: exact but not a usage: $it" } } +
            symbols.flatMap { s -> s.oracle.filter { it !in s.exact && it !in s.candidate }.map { "${s.query}: usage not listed: $it" } }
        if (wrong.isNotEmpty()) {
            appendLine()
            wrong.forEach { appendLine("- $it") }
        }
    }

    private fun ratio(a: Int, b: Int) = if (b == 0) 1.0 else a.toDouble() / b

    private fun percent(x: Double) = String.format(Locale.ROOT, "%.1f %%", x * 100)
}
