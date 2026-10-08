package codeloupe.ingest

import java.io.InputStream

/**
 * Reads UTF-8 lines from [input], which starts at byte [startOffset] of its file, and tells where each line ends. A line
 * longer than [MAX_LINE] bytes is skipped (its text is null) rather than held in memory.
 */
internal class LineReader(private val input: InputStream, startOffset: Long) {
    /** [end] is the file offset after the line and its newline; [terminated] is false for a last line the writer has not finished. */
    class Line(val text: String?, val end: Long, val terminated: Boolean)

    private val buffer = ByteArray(BUFFER)
    private var length = 0
    private var position = 0
    private var offset = startOffset
    private var line = ByteArray(INITIAL_LINE)
    private var lineLength = 0
    private var oversized = false

    fun next(): Line? {
        while (true) {
            if (position == length) {
                length = input.read(buffer)
                position = 0
                if (length <= 0) {
                    length = 0
                    return if (lineLength == 0 && !oversized) null else finish(terminated = false)
                }
            }
            var i = position
            while (i < length && buffer[i] != NEWLINE) i++
            append(position, i)
            offset += i - position
            if (i < length) {
                position = i + 1
                offset++
                return finish(terminated = true)
            }
            position = length
        }
    }

    private fun append(from: Int, to: Int) {
        val n = to - from
        if (n == 0 || oversized) return
        if (lineLength + n > MAX_LINE) {
            oversized = true
            lineLength = 0
            line = ByteArray(INITIAL_LINE)
            return
        }
        if (lineLength + n > line.size) line = line.copyOf(maxOf(line.size * 2, lineLength + n))
        System.arraycopy(buffer, from, line, lineLength, n)
        lineLength += n
    }

    private fun finish(terminated: Boolean): Line {
        val end = if (lineLength > 0 && line[lineLength - 1] == CARRIAGE_RETURN) lineLength - 1 else lineLength
        val text = if (oversized) null else String(line, 0, end, Charsets.UTF_8)
        lineLength = 0
        oversized = false
        if (line.size > KEEP_LINE) line = ByteArray(INITIAL_LINE)
        return Line(text, offset, terminated)
    }

    companion object {
        const val MAX_LINE = 32 * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private const val INITIAL_LINE = 8 * 1024
        private const val KEEP_LINE = 1024 * 1024
        private const val NEWLINE = '\n'.code.toByte()
        private const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}
