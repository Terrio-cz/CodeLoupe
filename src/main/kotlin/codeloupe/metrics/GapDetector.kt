package codeloupe.metrics

import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * Finds the gaps of CodeLoupe calls in one run: a call followed within [WINDOW] assistant turns by a code read, search or
 * shell search that names the same symbol or file; a call answered empty, busy, or with candidates only.
 */
object GapDetector {
    private const val WINDOW = 2
    private val FALLBACK = setOf("code_read", "code_search", "code_search_shell")
    private val EMPTY = Regex("^(no declaration|no match|no type|no indexed file|no issue|no tasks|no ready tasks)")
    private val CANDIDATES_ONLY = Regex("\\n0 exact, [1-9]\\d* candidate")
    private val CLI_TOOL = Regex("""codeloupe(?:\.bat)?\s+(?:--\S+\s+\S+\s+)*([a-z_]+)(.*)""")
    private val SUBJECT_ARGS = listOf("name", "q", "target", "pattern", "query", "id")

    fun detect(run: Run): List<Gap> {
        val week = week(run.start)
        val calls = run.tools.filter { it.category == "codeloupe" }
        return calls.flatMap { call ->
            val (tool, subject) = identify(call) ?: return@flatMap emptyList()
            val shape = "$tool:${shape(subject)}"
            val token = token(subject)
            buildList {
                if (call.head.startsWith("busy:")) add(Gap(week, tool, shape, "busy", token))
                if (EMPTY.containsMatchIn(call.head)) add(Gap(week, tool, shape, "empty", token))
                if (CANDIDATES_ONLY.containsMatchIn(call.head)) add(Gap(week, tool, shape, "candidates", token))
                if (token != null && run.tools.any { follows(call, it, token) }) add(Gap(week, tool, shape, "fallback", token))
            }
        }
    }

    private fun follows(call: ToolCall, next: ToolCall, token: String): Boolean =
        next.seq > call.seq && next.turn <= call.turn + WINDOW && next.category in FALLBACK && mentions(next, token)

    private fun mentions(call: ToolCall, token: String): Boolean {
        val text = call.input.values.joinToString("\n") { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty() }
        return Regex("(?<![A-Za-z0-9_])${Regex.escape(token)}(?![A-Za-z0-9_])").containsMatchIn(text)
    }

    /** The tool called and the text it was asked about, from an MCP call's arguments or a CLI command line. */
    private fun identify(call: ToolCall): Pair<String, String?>? {
        if (call.name.startsWith("mcp__codeloupe__")) {
            val subject = SUBJECT_ARGS.firstNotNullOfOrNull { (call.input[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { s -> s.isNotEmpty() } }
            return call.name.removePrefix("mcp__codeloupe__") to subject
        }
        val command = (call.input["command"] as? JsonPrimitive)?.content ?: return null
        val match = CLI_TOOL.find(command) ?: return null
        val subject = Regex(""""[^"]*"|'[^']*'|\S+""").findAll(match.groupValues[2]).map { it.value.trim('"', '\'') }.firstOrNull { !it.startsWith("-") }
        return match.groupValues[1] to subject
    }

    /** `*Repo` glob, `f(Type)` overload, `Type.member` qualified, a path, or a plain name. */
    private fun shape(subject: String?): String = when {
        subject == null -> "-"
        subject.contains('*') || subject.contains('?') -> "glob"
        subject.contains('(') -> "overload"
        subject.contains('/') || subject.endsWith(".kt") || subject.endsWith(".java") -> "path"
        subject.contains('.') -> "qualified"
        else -> "name"
    }

    /** What a fallback search would contain: a file's name, or the last identifier of a symbol. */
    private fun token(subject: String?): String? {
        if (subject == null) return null
        if (subject.contains('/') || subject.endsWith(".kt") || subject.endsWith(".java")) return subject.substringAfterLast('/').substringBefore(':')
        return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(subject.substringBefore('(')).map { it.value }.lastOrNull()?.takeIf { it.length > 2 }
    }

    private fun week(start: String?): String {
        val at = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "unknown"
        val date = at.atOffset(ZoneOffset.UTC)
        return "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }
}
