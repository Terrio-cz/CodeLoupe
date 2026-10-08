package codeloupe.hooks

import codeloupe.JsonFormat
import codeloupe.config.HooksConfig
import codeloupe.metrics.TranscriptFile
import codeloupe.metrics.arr
import codeloupe.metrics.obj
import codeloupe.metrics.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

/**
 * Feeds the shell, PowerShell and Read calls of Claude Code transcripts, in order, to the same decision the hook endpoint makes,
 * and counts what it would have said. Nothing is stored or printed of a command; the files are looked at as they are now.
 */
class HookReplay(private val config: HooksConfig = HooksConfig(), private val sources: ReplaySources = ReplaySources()) {
    private val advice = ArrayList<HookRecord>()
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    private val steering = Steering(sources, ShellPaths(System.getProperty("user.home").replace('\\', '/'), windows))
    private val codeCalls = HashMap<String, Int>()
    private var session = ""

    // The session stands for the repository: a CodeLoupe call anywhere in it counts as the advice being taken.
    private val hooks = Hooks(
        steering, { config }, { line -> advice += JsonFormat.json.decodeFromString(HookRecord.serializer(), line) }, { session }, { codeCalls[it] ?: 0 },
    )

    fun run(files: List<TranscriptFile>): ReplayReport {
        val calls = sortedMapOf<String, Int>()
        val roles = HashMap<String, ReplayReport.RoleCounts>()
        val programs = sortedMapOf<String, MutableMap<String, Int>>()
        for (file in files) {
            var examined = 0
            val before = advice.size
            for (call in calls(file)) {
                if (call.tool == CODELOUPE) {
                    codeCalls.merge(call.session, 1, Int::plus)
                    continue
                }
                session = call.session
                calls.merge(call.tool, 1, Int::plus)
                examined++
                sources.current = call
                watched(call)?.let { program ->
                    val verdict = steering.judge(call.tool, call.input, call.cwd, config.steer.minLines)
                    programs.getOrPut(program) { sortedMapOf() }.merge(if (verdict is Verdict.Skip) verdict.reason else "advised", 1, Int::plus)
                }
                hooks.handle(
                    JsonObject(
                        mapOf(
                            "hook_event_name" to JsonPrimitive("PreToolUse"), "session_id" to JsonPrimitive(call.session), "cwd" to JsonPrimitive(call.cwd),
                            "tool_name" to JsonPrimitive(call.tool), "tool_input" to call.input, "tool_use_id" to JsonPrimitive(call.id),
                        ),
                    ),
                )
            }
            roles.merge(file.role, ReplayReport.RoleCounts(examined, advice.size - before)) { a, b -> ReplayReport.RoleCounts(a.calls + b.calls, a.advised + b.advised) }
        }
        val stats = hooks.stats()
        return ReplayReport(
            transcripts = files.size, calls = calls, advised = stats.advised.toInt(), denied = stats.denied.toInt(),
            byKind = advice.groupingBy { "${it.why} -> ${it.suggested}" }.eachCount(), passed = stats.passed.mapValues { it.value.toInt() },
            byRole = roles, programs = programs, mode = config.steer.mode, minLines = config.steer.minLines,
        )
    }

    // A call and its result are far apart in the file: the results of the calls that read source files are looked up by id.
    private fun calls(file: TranscriptFile): List<ReplayCall> {
        val calls = LinkedHashMap<String, ReplayCall>()
        Files.newBufferedReader(file.path).use { reader ->
            for (line in reader.lineSequence()) {
                if (line.contains("\"tool_use\"")) {
                    val entry = runCatching { Json.parseToJsonElement(line).obj() }.getOrNull() ?: continue
                    val cwd = entry["cwd"].str() ?: continue
                    val session = entry["sessionId"].str() ?: file.path.fileName.toString()
                    for (block in entry["message"].obj()?.get("content").arr().orEmpty()) {
                        val use = block.obj()?.takeIf { it["type"].str() == "tool_use" } ?: continue
                        val tool = use["name"].str()?.let { if (isCodeLoupe(it, use["input"].obj())) CODELOUPE else it }?.takeIf { it in TOOLS || it == CODELOUPE } ?: continue
                        val id = use["id"].str() ?: continue
                        val input = use["input"].obj() ?: continue
                        calls.putIfAbsent(id, ReplayCall(tool, input, cwd.replace('\\', '/'), session, id))
                    }
                } else if (line.contains("\"tool_result\"")) {
                    val id = RESULT_ID.find(line)?.groupValues?.get(1) ?: continue
                    val call = calls[id]?.takeIf { it.lines == null && readsSource(it) } ?: continue
                    val entry = runCatching { Json.parseToJsonElement(line).obj() }.getOrNull() ?: continue
                    calls[id] = call.copy(lines = resultLines(entry))
                }
            }
        }
        return calls.values.toList()
    }

    /** The search or read program a shell call starts with (`rg`, `cat`, …), for the table of what the decision made of each. */
    private fun watched(call: ReplayCall): String? {
        val command = (call.input["command"] as? JsonPrimitive)?.content ?: return null
        return ShellWords.split(command).map { it.program.replace('\\', '/').substringAfterLast('/').lowercase().removeSuffix(".exe") }.firstOrNull { it in WATCHED }
    }

    private fun isCodeLoupe(name: String, input: JsonObject?): Boolean =
        name.startsWith("mcp__") && name.contains("codeloupe") || name == "Bash" && (input?.get("command") as? JsonPrimitive)?.content.orEmpty().trimStart().startsWith("codeloupe ")

    private fun readsSource(call: ReplayCall): Boolean =
        call.tool == "Read" || SOURCE_IN_TEXT.containsMatchIn((call.input["command"] as? JsonPrimitive)?.content.orEmpty())

    private fun resultLines(entry: JsonObject): Int? {
        val total = entry["toolUseResult"].obj()?.get("file").obj()?.get("totalLines")
        (total as? JsonPrimitive)?.content?.toIntOrNull()?.let { return it }
        val text = entry["message"].obj()?.get("content").arr().orEmpty().firstNotNullOfOrNull { block ->
            block.obj()?.takeIf { it["type"].str() == "tool_result" }?.get("content")?.let { it.str() ?: it.arr()?.joinToString("\n") { part -> part.obj()?.get("text").str().orEmpty() } }
        } ?: return null
        return if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    }

    private companion object {
        const val CODELOUPE = "CodeLoupe"
        val TOOLS = setOf("Bash", "PowerShell", "Read")
        val WATCHED = setOf("rg", "grep", "egrep", "fgrep", "cat", "head", "tail", "sed", "find", "get-content", "select-string")
        val RESULT_ID = Regex(""""tool_use_id":"([^"]+)"""")
        val SOURCE_IN_TEXT = Regex("""(?i)\.(kt|kts|java)""")
    }
}
