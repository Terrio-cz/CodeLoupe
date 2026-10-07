package codeloupe.changes

/** A compact line diff of two declaration texts: changed lines with one line of context, hunks split by `…`. */
object TextDiff {
    // Longest-common-subsequence table: lines² cells. Larger declarations are summarised instead.
    private const val MAX_CELLS = 250_000
    private const val CONTEXT = 1

    fun of(before: String, after: String): String {
        val a = lines(before)
        val b = lines(after)
        if (a.size.toLong() * b.size > MAX_CELLS) return "    (${a.size} → ${b.size} lines; too long to diff here, use symbol)"
        val ops = ops(a, b)
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { i, op ->
            if (op.first != ' ') for (j in maxOf(0, i - CONTEXT)..minOf(ops.lastIndex, i + CONTEXT)) keep[j] = true
        }
        return buildString {
            var gap = false
            ops.forEachIndexed { i, (mark, line) ->
                if (!keep[i]) {
                    gap = true
                    return@forEachIndexed
                }
                if (gap && isNotEmpty()) append("    …\n")
                gap = false
                append("    ").append(mark).append(' ').append(line).append('\n')
            }
        }.trimEnd('\n')
    }

    private fun ops(a: List<String>, b: List<String>): List<Pair<Char, String>> {
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val ops = ArrayList<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && a[i] == b[j] -> ops += ' ' to a[i++].also { j++ }
                // Removed lines before added ones, as diff prints them.
                i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> ops += '-' to a[i++]
                else -> ops += '+' to b[j++]
            }
        }
        return ops
    }

    private fun lines(text: String) = text.split('\n').map { it.trimEnd('\r') }
}
