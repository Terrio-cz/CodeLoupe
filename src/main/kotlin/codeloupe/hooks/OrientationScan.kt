package codeloupe.hooks

import codeloupe.metrics.TranscriptFile
import codeloupe.metrics.arr
import codeloupe.metrics.obj
import codeloupe.metrics.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * Counts the orientation calls of the first [turns] assistant turns of each session transcript, apart for the sessions that
 * began with the session-start hook's context (its first line is in the transcript's opening lines). Subagent runs are
 * left out: they are given a task, not a repository to find their way in.
 */
class OrientationScan(private val turns: Int = TURNS) {
    private class Tally {
        var sessions = 0
        var calls = 0
        var withAny = 0
        val kinds = sortedMapOf<String, Int>()

        fun add(found: List<String>) {
            sessions++
            calls += found.size
            if (found.isNotEmpty()) withAny++
            found.forEach { kinds.merge(it, 1, Int::plus) }
        }

        fun group() = OrientationReport.Group(sessions, calls, withAny, kinds.toMap())
    }

    fun run(files: List<TranscriptFile>): OrientationReport {
        val with = Tally()
        val without = Tally()
        for (file in files.filter { it.kind == "session" }) {
            val (hooked, found) = read(file) ?: continue
            (if (hooked) with else without).add(found)
        }
        return OrientationReport(turns, with.group(), without.group())
    }

    private fun read(file: TranscriptFile): Pair<Boolean, List<String>>? {
        var hooked = false
        var turn = 0
        var seen = 0
        val found = ArrayList<String>()
        val ids = HashSet<String>()
        Files.newBufferedReader(file.path).use { reader ->
            for (line in reader.lineSequence()) {
                if (seen++ < OPENING_LINES && line.contains(MARKER)) hooked = true
                if (!line.contains("\"assistant\"")) continue
                val message = runCatching { Json.parseToJsonElement(line).obj() }.getOrNull()?.takeIf { it["type"].str() == "assistant" }?.get("message").obj() ?: continue
                val id = message["id"].str()
                if (id != null && ids.add(id)) turn++
                if (turn > turns) break
                for (block in message["content"].arr().orEmpty()) {
                    val use = block.obj()?.takeIf { it["type"].str() == "tool_use" } ?: continue
                    kind(use["name"].str().orEmpty(), (use["input"].obj()?.get("command") as? JsonPrimitive)?.content)?.let { found += it }
                }
            }
        }
        return if (turn == 0) null else hooked to found
    }

    private fun kind(tool: String, command: String?): String? {
        if (tool == "Glob") return "Glob"
        if (tool != "Bash" && tool != "PowerShell") return null
        val program = ShellWords.split(command.orEmpty()).map { it.program.replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe") }.firstOrNull { it != "cd" && it != "set-location" }
        return program?.takeIf { it in SHELL }?.let { if (it == "get-childitem" || it == "gci" || it == "dir") "ls" else it }
    }

    companion object {
        const val TURNS = 8
        const val MARKER = "CodeLoupe orientation for"
        private const val OPENING_LINES = 80
        private val SHELL = setOf("ls", "find", "tree", "dir", "get-childitem", "gci")
    }
}
