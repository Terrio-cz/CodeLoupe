package codeloupe.hooks

import codeloupe.metrics.TranscriptFile
import codeloupe.metrics.TranscriptParser
import codeloupe.metrics.Usage
import kotlinx.serialization.Serializable
import java.nio.file.Files

/**
 * Runs whole transcripts through the weight verdict afterwards: at which turn each of the longest sessions would have been warned at
 * each size, and how much of the context its heaviest results held then. Counts only.
 */
class WeightReplay(private val warnAt: List<Int> = listOf(100_000, 150_000), private val top: Int = 3) {
    @Serializable
    data class Report(val sessions: Int, val turns: List<Int>, val peaks: List<Long>, val sizes: List<Size>, val top: Int) {
        fun render(): String = buildString {
            appendLine("the $sessions longest main sessions (turns ${stat(turns.sorted())}, peak context ${stat(peaks.sorted().map { it / 1_000 })}k tokens)")
            sizes.forEach { s ->
                appendLine(
                    "  at ${s.tokens / 1_000}k tokens: warned in ${s.warnedAt.size} of $sessions" +
                        if (s.warnedAt.isEmpty()) "" else ", at turn ${stat(s.warnedAt.sorted())}; the $top heaviest results held ${stat(s.share.sorted())} % of the context then",
                )
            }
        }.trimEnd()

        private fun <T : Comparable<T>> stat(sorted: List<T>) = if (sorted.isEmpty()) "-" else "median ${sorted[sorted.size / 2]} (${sorted.first()}-${sorted.last()})"
    }

    @Serializable
    data class Size(val tokens: Int, val warnedAt: List<Int>, val share: List<Int>)

    private class Run(val turns: Int, val contexts: List<Long>, val items: List<Triple<Int, String, Int>>)

    fun run(files: List<TranscriptFile>, longest: Int): Report {
        val runs = files.filter { it.kind == "session" }.mapNotNull(::read).sortedByDescending { it.turns }.take(longest)
        val sizes = warnAt.map { size ->
            val warned = ArrayList<Int>()
            val share = ArrayList<Int>()
            for (run in runs) {
                val turn = run.contexts.indexOfFirst { it >= size }.takeIf { it >= 0 }?.plus(1) ?: continue
                warned += turn
                share += heavyShare(run, turn)
            }
            Size(size, warned, share)
        }
        return Report(runs.size, runs.map { it.turns }, runs.map { it.contexts.maxOrNull() ?: 0 }, sizes, top)
    }

    private fun read(file: TranscriptFile): Run? {
        val parser = TranscriptParser("main")
        Files.newBufferedReader(file.path).use { reader -> reader.lineSequence().filter { it.isNotEmpty() }.forEach(parser::feed) }
        val (results, usages) = parser.drain()
        if (parser.turns == 0) return null
        return Run(parser.turns, usages.map { it.usage.context() }, results.map { Triple(it.turn, it.name, it.chars) })
    }

    // The share the heaviest results held at [turn], by the same ranking the live hook uses.
    private fun heavyShare(run: Run, turn: Int): Int {
        val context = run.contexts[turn - 1]
        val held = run.items.filter { it.first <= turn }.sortedByDescending { it.third.toLong() * (turn - it.first + 1) }.take(top).sumOf { it.third / WeightState.CHARS_PER_TOKEN }
        return if (context <= 0) 0 else minOf(100, (held * 100 / context).toInt())
    }

    private fun Usage.context() = input + cw5m + cw1h + cacheRead
}
