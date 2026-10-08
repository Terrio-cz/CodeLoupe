package codeloupe.hooks

import codeloupe.ingest.LineReader
import codeloupe.metrics.TranscriptParser
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path

/**
 * The weight of one transcript, read incrementally: each call reads only what was appended since the last one, with the same
 * parser the metrics use. A last line the writer has not finished stays for next time.
 */
internal class WeightState(private val path: Path) {
    private class Item(val turn: Int, val tool: String, val chars: Int)

    private var parser = TranscriptParser("main")
    private val items = ArrayList<Item>()
    private var context = 0L

    @Volatile
    var offset = 0L
        private set

    /** Reads what the transcript gained; false when it shrank (rewritten), in which case the state starts over. */
    @Synchronized
    fun advance(): Boolean {
        val size = Files.size(path)
        if (size < offset) reset()
        if (size == offset) return true
        val consumed = Files.newByteChannel(path).use { channel ->
            channel.position(offset)
            val lines = LineReader(Channels.newInputStream(channel), offset)
            var end = offset
            while (true) {
                val line = lines.next() ?: break
                val text = line.text
                if (!line.terminated && (text.isNullOrEmpty() || !parser.feed(text))) break
                if (line.terminated && !text.isNullOrEmpty()) parser.feed(text)
                end = line.end
            }
            end
        }
        val (results, usages) = parser.drain()
        results.forEach { items += Item(it.turn, it.name, it.chars) }
        usages.lastOrNull()?.let { context = tokens(it.usage) }
        offset = consumed
        return true
    }

    @Synchronized
    fun weight(warnAt: List<Int>, top: Int): SessionWeight {
        val turns = parser.turns
        val heavy = items.sortedByDescending { it.chars.toLong() * (turns - it.turn + 1) }.take(top)
            .filter { it.chars > 0 }.map { SessionWeight.Heavy(it.turn, it.tool, it.chars / CHARS_PER_TOKEN) }
        return SessionWeight(context, turns, heavy, level(context, warnAt), complete = true)
    }

    private fun reset() {
        parser = TranscriptParser("main")
        items.clear()
        context = 0
        offset = 0
    }

    companion object {
        /** The project's estimate for the size of a tool result (`attr` of the metrics). */
        const val CHARS_PER_TOKEN = 4L

        fun level(context: Long, warnAt: List<Int>): Int = warnAt.count { context >= it }

        private fun tokens(u: codeloupe.metrics.Usage) = u.input + u.cw5m + u.cw1h + u.cacheRead

        /** The context at the last assistant line of the final [TAIL] bytes: an answer for a transcript not yet read, in a few milliseconds. */
        fun tailContext(path: Path): Long {
            val parser = TranscriptParser("main")
            RandomAccessFile(path.toFile(), "r").use { file ->
                val start = maxOf(0L, file.length() - TAIL)
                file.seek(start)
                val bytes = ByteArray((file.length() - start).toInt()).also { file.readFully(it) }
                val text = String(bytes, Charsets.UTF_8)
                // The first line of the tail is cut in the middle unless the tail starts the file.
                text.lineSequence().drop(if (start > 0) 1 else 0).filter { it.isNotEmpty() }.forEach(parser::feed)
            }
            return parser.drain().second.lastOrNull()?.let { tokens(it.usage) } ?: 0
        }

        private const val TAIL = 1_048_576L
    }
}
