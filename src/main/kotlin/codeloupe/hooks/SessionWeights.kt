package codeloupe.hooks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The weight of the transcripts of the sessions that ask, each read incrementally and kept in memory (the last [MAX_STATES]).
 * A call reads only what was appended since the previous one. A transcript that is far ahead of what was read - a session
 * seen for the first time, a daemon restarted in the middle of one - is read in the background, and meanwhile the answer is
 * the context at its last turn, taken from the tail of the file, without the heavy results.
 */
class SessionWeights(private val scope: CoroutineScope, private val catchUpBytes: Long = CATCH_UP_BYTES) {
    private class Entry(val state: WeightState) {
        val reading = AtomicBoolean(false)
    }

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>) = size > MAX_STATES
    }

    /** Null for a path that is not a readable file. */
    fun weigh(path: Path, warnAt: List<Int>, top: Int): SessionWeight? {
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        val entry = synchronized(entries) { entries.getOrPut(path.toAbsolutePath().normalize().toString()) { Entry(WeightState(path)) } }
        if (size - entry.state.offset > catchUpBytes) {
            if (entry.reading.compareAndSet(false, true)) scope.launch(Dispatchers.IO) { try { entry.state.advance() } finally { entry.reading.set(false) } }
            val context = runCatching { WeightState.tailContext(path) }.getOrDefault(0)
            return SessionWeight(context, 0, emptyList(), WeightState.level(context, warnAt), complete = false)
        }
        entry.state.advance()
        return entry.state.weight(warnAt, top)
    }

    private companion object {
        const val MAX_STATES = 16
        const val CATCH_UP_BYTES = 2L * 1024 * 1024
    }
}
