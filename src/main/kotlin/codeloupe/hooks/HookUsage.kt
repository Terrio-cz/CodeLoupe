package codeloupe.hooks

import codeloupe.JsonFormat
import codeloupe.daemon.CallRecord
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * How the steering hook did in real sessions: what `hooks.jsonl` says it said, and whether a CodeLoupe code call on the same
 * worktree followed within [FOLLOW] (`calls.jsonl`), which is how the advice being taken shows. Counts only.
 */
class HookUsage(private val home: Path) {
    data class Row(val kind: String, val spoken: Int, val followed: Int)

    data class Result(val rows: List<Row>, val denied: Int, val since: Instant?) {
        val spoken get() = rows.sumOf { it.spoken }
        val followed get() = rows.sumOf { it.followed }

        fun render(): String {
            if (spoken == 0) return "the steering hook has not spoken" + (since?.let { " since $it" } ?: "")
            val lines = ArrayList<String>()
            lines += "steering hook: $spoken pieces of advice" + (if (denied > 0) " ($denied refused first)" else "") +
                ", $followed followed by a CodeLoupe call within ${FOLLOW.toSeconds()} s"
            rows.sortedByDescending { it.spoken }.forEach { lines += "  ${it.spoken}  ${it.kind}  followed ${it.followed} (${it.followed * 100 / it.spoken} %)" }
            return lines.joinToString("\n")
        }
    }

    fun since(since: Instant?): Result {
        val records = lines(home.resolve("hooks.jsonl")).mapNotNull { runCatching { JsonFormat.json.decodeFromString(HookRecord.serializer(), it) }.getOrNull() }
            .filter { since == null || stamp(it.t) >= since }
        val calls = lines(home.resolve("calls.jsonl")).mapNotNull { runCatching { JsonFormat.json.decodeFromString(CallRecord.serializer(), it) }.getOrNull() }
            .filter { (it.via == "mcp" || it.via == "api") && it.tool in CODE_TOOLS }.map { stamp(it.t) to it.root }
        val rows = records.groupBy { "${it.why} -> ${it.suggested}" }.map { (kind, same) ->
            Row(kind, same.size, same.count { record -> calls.any { (at, root) -> root == record.root && at > stamp(record.t) && at <= stamp(record.t) + FOLLOW } })
        }
        return Result(rows, records.count { it.decision == "denied" }, since)
    }

    private fun lines(file: Path): List<String> = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())

    private fun stamp(text: String): Instant = runCatching { Instant.parse(text) }.getOrDefault(Instant.EPOCH)

    companion object {
        val FOLLOW: Duration = Duration.ofSeconds(180)
        private val CODE_TOOLS = setOf("find", "grep", "outline", "symbol", "context", "usages", "calls", "hierarchy", "changes")
    }
}
