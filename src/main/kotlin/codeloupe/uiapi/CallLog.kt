package codeloupe.uiapi

import codeloupe.JsonFormat
import codeloupe.daemon.CallRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.useLines

/**
 * Reads the call telemetry (`calls.jsonl` and its rolled `.1`) back for the UI API. The parsed lines are kept until
 * either file changes, so a screen that asks every few seconds does not parse ten megabytes each time.
 */
internal class CallLog(private val file: Path, private val clock: () -> Instant = Instant::now) {
    private var stamp: List<Long> = emptyList()
    private var records: List<Timed> = emptyList()

    private class Timed(val at: Instant, val record: CallRecord)

    /** Calls of the last [days] days, oldest first. */
    @Synchronized
    fun since(days: Long): List<CallRecord> {
        val files = listOf(Path.of("$file.1"), file).filter { it.exists() }
        val now = files.map { Files.size(it) xor it.getLastModifiedTime().toMillis() }
        if (now != stamp) {
            records = files.flatMap(::parse)
            stamp = now
        }
        val from = clock().minusSeconds(days * 86_400)
        return records.filter { !it.at.isBefore(from) }.map { it.record }
    }

    private fun parse(path: Path): List<Timed> = path.useLines { lines ->
        lines.mapNotNull { line ->
            runCatching { JsonFormat.json.decodeFromString(CallRecord.serializer(), line) }.getOrNull()
                ?.let { r -> runCatching { Timed(Instant.parse(r.t), r) }.getOrNull() }
        }.toList()
    }

    /** Calls whose [CallRecord.t] is after [from]. */
    fun after(from: Instant, days: Long = 31): List<CallRecord> = since(days).filter { runCatching { Instant.parse(it.t).isAfter(from) }.getOrDefault(false) }
}
