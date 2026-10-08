package codeloupe.triage

/**
 * Failed tests of a build summary: the first assertion with its expected and actual value, and the frames of our own code
 * with the declaration each is in; the first frame in production code also carries the `symbol` call and hash.
 */
object TestTriage {
    private val FRAME = Regex("""^\s+at (?:app//)?([\w.$]+)\.[^.(]+\((\w+\.(?:kt|java)):(\d+)\)$""")
    private val ASSERTION = Regex("""^\s+(?:[\w.$]+\.)?(\w*(?:AssertionFailedError|AssertionError|ComparisonFailure|ComparisonCompactor)\w*): (.*)$""")
    private val EXPECTED = Regex("""expected:?\s*(<.*?>|\[.*?\]|\S+)\s*but was:?\s*(<.*?>|\[.*?\]|\S+)""", RegexOption.DOT_MATCHES_ALL)
    private const val MAX = 160

    fun apply(lines: List<String>, locator: DeclLocator): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("FAILED ")) {
                out += lines[i++]
                continue
            }
            var end = i + 1
            while (end < lines.size && lines[end].startsWith(" ")) end++
            out += block(lines.subList(i, end), locator)
            i = end
        }
        return out
    }

    private fun block(block: List<String>, locator: DeclLocator): List<String> {
        val frames = block.drop(1).mapNotNull { line -> FRAME.find(line)?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3].toInt()) } }
        val located = frames.mapNotNull { (cls, file, line) -> locator.inFrame(cls, file, line)?.let { Triple(file, line, it) } }
        if (located.isEmpty()) return block
        val head = block.drop(1).firstOrNull { FRAME.find(it) == null }
        val out = arrayListOf(block[0])
        if (head != null) out += "  " + assertion(head)
        val production = located.indexOfFirst { !locator.isTest(it.third.path) }.takeIf { it >= 0 } ?: 0
        located.forEachIndexed { n, (file, line, pointer) ->
            out += "  at $file:$line" + when {
                n == production && locator.isTest(pointer.path) -> "  · ${pointer.next()}"
                n == production -> "  ${pointer.label}  · ${pointer.next()}"
                else -> ""
            }
        }
        return out
    }

    /** `expected <a>, was <b>` for an assertion that names both; any other message as it came, without the exception's package. */
    private fun assertion(line: String): String {
        val m = ASSERTION.find(line) ?: return clip(line.trim())
        val both = EXPECTED.find(m.groupValues[2])
        return clip(if (both != null) "expected ${both.groupValues[1]}, was ${both.groupValues[2]}" else "${m.groupValues[1]}: ${m.groupValues[2]}")
    }

    private fun clip(text: String) = if (text.length <= MAX) text else text.take(MAX - 1) + "…"
}
