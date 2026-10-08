package codeloupe.write

/** Line and column of the index (1-based, UTF-16 units, only `\n` ends a line) as an offset into the text. */
internal class LineOffsets(text: String) {
    private val starts: IntArray = (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()

    fun offset(line: Int, column: Int): Int? = starts.getOrNull(line - 1)?.plus(column - 1)
}
