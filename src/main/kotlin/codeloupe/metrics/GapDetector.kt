package codeloupe.metrics

import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * Finds the gaps of CodeLoupe calls in one run: a call followed within [WINDOW] assistant turns by a code read, search or
 * shell search that names the same symbol or file; a call answered busy, or with candidates only; an empty answer that the
 * agent then looked for elsewhere.
 *
 * Two things are not fallbacks: a read of a line range (`Read` with offset or limit, `sed -n 10,40p`), which is how the lines an
 * `outline`, `find` or `symbol` answer pointed at are read, and anything after a tracker call (`issue`, `tasks`, a task id as the
 * subject of `task_code`), whose id is no word of code.
 *
 * Two things are not gaps (CL-198, [GapRule.CURRENT]): an empty answer the agent accepted (a correct negative for a name the task
 * has yet to create, reported as `settled`), and a later search or read for a word too common to mean the same thing (`json`,
 * `description`) unless it reads a code file the answer itself named. [GapRule.LEGACY] counts both, the way the series began.
 */
object GapDetector {
    private const val WINDOW = 2
    private const val SETTLED = "settled"
    private const val MIN_DISTINCTIVE = 5
    private val FALLBACK = setOf("code_read", "code_search", "code_search_shell")
    private val EMPTY = Regex("^(no declaration|no match|no type|no indexed file|no issue|no tasks|no ready tasks)")
    private val CANDIDATES_ONLY = Regex("\\n0 exact, [1-9]\\d* candidate")
    private val CLI_TOOL = Regex("""codeloupe(?:\.bat)?\s+(?:--\S+\s+\S+\s+)*([a-z_]+)(.*)""")
    private val SUBJECT_ARGS = listOf("name", "q", "target", "pattern", "query", "id")
    private val LEADING_CD = Regex("""^cd\s+\S+\s*(&&|;)\s*""")
    private val SHELL_PROGRAMS = setOf("rg", "grep", "sed", "cat")
    private val TRACKER_TOOLS = setOf("issue", "tasks", "task_context", "dispatch_plan", "similar", "update")
    private val TASK_ID = Regex("\\b[A-Za-z][A-Za-z0-9_]*-\\d+\\b")
    private val LINE_RANGE = Regex("""\bsed\s+-n\s+'?\d+,\d+p""")
    private val FILE_NAME = Regex("""[A-Za-z0-9_\-]+\.[A-Za-z][A-Za-z0-9]{0,5}""")

    /** Words that turn up in any document, so a search for one after a CodeLoupe call says nothing about what the call was asked. */
    private val COMMON_WORDS = setOf(
        "description", "website", "content", "contents", "message", "messages", "status", "result", "results", "string", "number", "value", "values",
        "request", "response", "default", "option", "options", "error", "errors", "title", "label", "config", "settings", "version", "example", "examples",
    )

    fun detect(run: Run, rule: GapRule = GapRule.CURRENT): List<Gap> = locate(run, rule).map { it.gap }

    fun locate(run: Run, rule: GapRule = GapRule.CURRENT): List<LocatedGap> = analyse(run, rule).filter { it.gap.kind != SETTLED }

    /** The empty answers the agent accepted: correct negatives, not gaps. */
    fun settled(run: Run): List<LocatedGap> = analyse(run, GapRule.CURRENT).filter { it.gap.kind == SETTLED }

    private fun analyse(run: Run, rule: GapRule): List<LocatedGap> {
        val week = week(run.start)
        val calls = run.tools.filter { it.category == "codeloupe" }
        return calls.flatMap { call ->
            val (tool, subject) = identify(call) ?: return@flatMap emptyList()
            val shape = "$tool:${shape(subject)}"
            val token = token(tool, subject)
            fun at(kind: String, fallback: String? = null) = LocatedGap(Gap(week, tool, shape, kind, token), call.seq, call.turn, fallback)
            buildList {
                val next = token?.let { t -> run.tools.firstOrNull { follows(call, it, t, subject, rule) } }
                if (call.head.startsWith("busy:")) add(at("busy"))
                if (EMPTY.containsMatchIn(call.head)) add(at(if (rule == GapRule.LEGACY || next != null) "empty" else SETTLED))
                if (CANDIDATES_ONLY.containsMatchIn(call.head)) add(at("candidates"))
                if (next != null) add(at("fallback", fallbackOf(next)))
            }
        }
    }

    /** What the agent used instead: `Read`, or the program of a shell search. */
    private fun fallbackOf(next: ToolCall): String = when {
        next.name == "Read" -> "Read"
        next.name == "Grep" -> "rg"
        else -> ((next.input["command"] as? JsonPrimitive)?.content?.trim()?.replaceFirst(LEADING_CD, "")?.substringBefore(' ')).orEmpty()
            .let { if (it in SHELL_PROGRAMS) it else "other" }
    }

    private fun follows(call: ToolCall, next: ToolCall, token: String, subject: String?, rule: GapRule): Boolean =
        next.seq > call.seq && next.turn <= call.turn + WINDOW && next.category in FALLBACK && !isRange(next) && mentions(next, token) &&
            (rule == GapRule.LEGACY || !isCommon(token) || readsNamedFile(call, next, subject))

    private fun isCommon(token: String) = token.length < MIN_DISTINCTIVE || token.lowercase() in COMMON_WORDS

    /** A code read of a file the answer (or the question) names: the one case where a common word still means the same thing. */
    private fun readsNamedFile(call: ToolCall, next: ToolCall, subject: String?): Boolean {
        if (next.category != "code_read") return false
        val named = FILE_NAME.findAll(call.head + "\n" + subject.orEmpty()).map { it.value }.toSet()
        return named.any { mentions(next, it) }
    }

    private fun isRange(call: ToolCall): Boolean =
        call.partial || LINE_RANGE.containsMatchIn((call.input["command"] as? JsonPrimitive)?.content.orEmpty())

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
    private fun token(tool: String, subject: String?): String? {
        if (subject == null || tool in TRACKER_TOOLS || (tool == "task_code" && TASK_ID.containsMatchIn(subject))) return null
        if (subject.contains('/') || subject.endsWith(".kt") || subject.endsWith(".java")) return subject.substringAfterLast('/').substringBefore(':')
        return Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(subject.substringBefore('(')).map { it.value }.lastOrNull()?.takeIf { it.length > 2 }
    }

    private fun week(start: String?): String {
        val at = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "unknown"
        val date = at.atOffset(ZoneOffset.UTC)
        return "%d-W%02d".format(java.util.Locale.ROOT, date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }
}
