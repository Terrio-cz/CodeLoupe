package codeloupe.metrics

import codeloupe.JsonFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * The baseline report the daemon compares against: `<home>/baseline.json`, which `codeloupe metrics collect --baseline`
 * writes. It is read again when the file changes, so a new baseline needs no restart.
 */
class BaselineStore(private val file: Path) {
    sealed interface State {
        data object Missing : State

        data class Unreadable(val message: String) : State

        data class Loaded(val baseline: Baseline) : State
    }

    private var stamp: Pair<Long, Long>? = null
    private var state: State = State.Missing

    @Synchronized
    fun state(): State {
        val now = runCatching { Files.getLastModifiedTime(file).toMillis() to Files.size(file) }.getOrNull()
        if (now == null) {
            stamp = null
            state = State.Missing
        } else if (now != stamp) {
            stamp = now
            state = load()
        }
        return state
    }

    private fun load(): State = try {
        val baseline = Baseline.of(JsonFormat.json.decodeFromString(MetricsReport.serializer(), Files.readString(file)))
        if (baseline.roles == 0) State.Unreadable("the report has no role with runs and a cost") else State.Loaded(baseline)
    } catch (e: Exception) {
        State.Unreadable("not a metrics report: ${e.message.orEmpty().lineSequence().first().take(MESSAGE_CHARS)}")
    }

    companion object {
        const val FILE = "baseline.json"
        private const val MESSAGE_CHARS = 120
    }
}
