package codeloupe.write

/** Code the caller sends, fitted into the file: the indentation of its place, the file's line ends. */
internal object Reindent {
    /**
     * [code] for a position whose line is already indented by [indent]: the common indentation of its lines is replaced by [indent]
     * on every line but the first, which continues the line. A text with a raw string (`"""`) is not re-indented: a changed line
     * inside would change the string; its first line loses its indentation, the rest stay as given.
     */
    fun block(code: String, indent: String, eol: String): String {
        val lines = trimmed(code)
        if (lines.isEmpty()) throw WriteRefused("the code is empty")
        val shifted = if (RAW in code) listOf(lines[0].trimStart()) + lines.drop(1) else shift(lines, indent)
        return shifted.joinToString(eol)
    }

    private fun shift(lines: List<String>, indent: String): List<String> {
        val common = commonIndent(lines)
        // A first line without the indentation its followers carry was cut from a larger text: they are measured by themselves.
        val rest = lines.drop(1)
        val restCommon = commonIndent(rest)
        val first = leading(lines[0])
        val cut = first.isEmpty() && restCommon.isNotEmpty() && leading(lines.last()) == restCommon && lines.last().trimStart().firstOrNull() in CLOSERS
        return lines.mapIndexed { i, line ->
            val prefix = if (cut && i > 0) restCommon else common
            when {
                line.isEmpty() -> ""
                // A blank line keeps whatever whitespace it had beyond the common indentation.
                line.isBlank() -> if (i > 0 && line.startsWith(prefix)) indent + line.removePrefix(prefix) else line
                i == 0 -> line.removePrefix(common)
                else -> indent + line.removePrefix(prefix)
            }
        }
    }

    private fun leading(line: String) = line.takeWhile { it == ' ' || it == '\t' }

    /** The lines of [code] with `\n` line ends, without blank lines at either end and without trailing blanks on the last line. */
    fun trimmed(code: String): List<String> =
        code.replace("\r\n", "\n").replace('\r', '\n').lines().dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.let { lines ->
            if (lines.isEmpty()) lines else lines.dropLast(1) + lines.last().trimEnd()
        }

    private fun commonIndent(lines: List<String>): String {
        val indents = lines.filter { it.isNotBlank() }.map { line -> line.takeWhile { it == ' ' || it == '\t' } }
        if (indents.isEmpty()) return ""
        return indents.reduce { a, b -> a.commonPrefixWith(b) }
    }

    /** The whitespace that starts the line holding [offset]. */
    fun indentAt(text: String, offset: Int): String {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset.coerceAtLeast(lineStart)).takeWhile { it == ' ' || it == '\t' }
    }

    /** True when only whitespace lies between the start of the line and [offset]. */
    fun startsLine(text: String, offset: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return text.substring(lineStart, offset).isBlank()
    }

    private const val RAW = "\"\"\""
    private val CLOSERS = setOf('}', ')', ']')
}
