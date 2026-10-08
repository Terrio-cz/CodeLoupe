package codeloupe.hooks

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** A Claude Code transcript made of turns: an assistant line with usage and one tool call, then the call's result. */
class SyntheticTranscript(val path: Path) {
    private var turn = 0

    /** One assistant turn whose request carried [context] tokens, calling [tool] and getting [resultChars] characters back. */
    fun turn(context: Long, tool: String = "Bash", resultChars: Int = 100, filler: Int = 0) {
        turn++
        val padding = "x".repeat(filler)
        append(
            """{"type":"assistant","timestamp":"2026-10-08T10:00:${"%02d".format(turn % 60)}Z","cwd":"/r","message":{"id":"m$turn","model":"m","usage":{"input_tokens":3,"cache_creation_input_tokens":0,"cache_read_input_tokens":${context - 3},"output_tokens":50},"content":[{"type":"tool_use","id":"u$turn","name":"$tool","input":{"command":"c","pad":"$padding"}}]}}""",
        )
        append("""{"type":"user","timestamp":"2026-10-08T10:00:${"%02d".format(turn % 60)}Z","message":{"content":[{"type":"tool_result","tool_use_id":"u$turn","content":"${"r".repeat(resultChars)}"}]}}""")
    }

    fun append(line: String, terminated: Boolean = true) {
        Files.writeString(path, line + if (terminated) "\n" else "", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun rewrite() {
        Files.deleteIfExists(path)
        turn = 0
    }
}
