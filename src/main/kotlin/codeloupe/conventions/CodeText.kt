package codeloupe.conventions

/** Source text with comments, string and character literals replaced by spaces (newlines kept), so patterns see code only. */
object CodeText {
    fun blank(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        fun blankTo(end: Int) {
            while (i < end) out.append(if (text[i] == '\n') '\n' else ' ').also { i++ }
        }
        while (i < n) {
            val c = text[i]
            when {
                text.startsWith("//", i) -> blankTo(text.indexOf('\n', i).let { if (it < 0) n else it })
                text.startsWith("/*", i) -> blankTo(blockEnd(text, i))
                text.startsWith("\"\"\"", i) -> blankTo(text.indexOf("\"\"\"", i + 3).let { if (it < 0) n else minOf(n, it + 3) })
                c == '"' || c == '\'' -> blankTo(quotedEnd(text, i))
                else -> out.append(c).also { i++ }
            }
        }
        return out.toString()
    }

    /** Kotlin block comments nest. */
    private fun blockEnd(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> { depth++; i += 2 }
                text.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return i }
                else -> i++
            }
        }
        return text.length
    }

    /** A literal ends at its closing quote or, when it never closes, at the end of the line. */
    private fun quotedEnd(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length && text[i] != '\n') {
            if (text[i] == '\\') i++ else if (text[i] == quote) return i + 1
            i++
        }
        return minOf(i, text.length)
    }
}
