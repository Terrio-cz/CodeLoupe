package codeloupe.lang.kotlin

/** Offset -> row/column of a text. Only `\n` ends a row; columns count UTF-16 units. */
internal class LineIndex(text: String) {
    private val rowStarts: IntArray = run {
        val starts = ArrayList<Int>().apply { add(0) }
        for (i in text.indices) if (text[i] == '\n') starts += i + 1
        starts.toIntArray()
    }

    /** 0-based row of an offset. */
    fun row(offset: Int): Int {
        val i = rowStarts.binarySearch(offset)
        return if (i >= 0) i else -i - 2
    }

    fun line(offset: Int): Int = row(offset) + 1

    fun column(offset: Int): Int = offset - rowStarts[row(offset)]

    /** 1-based last line of a span; a span that ends right after a newline ends on the line before. */
    fun endLine(start: Int, end: Int): Int {
        val endRow = row(end)
        return endRow + if (column(end) == 0 && endRow > row(start)) 0 else 1
    }
}
