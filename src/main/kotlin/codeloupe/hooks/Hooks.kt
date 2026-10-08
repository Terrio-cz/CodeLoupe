package codeloupe.hooks

import codeloupe.JsonFormat
import codeloupe.config.Config
import codeloupe.config.ConfigLoader
import codeloupe.config.HooksConfig
import codeloupe.daemon.AppendLog
import codeloupe.platform.IsoTime
import codeloupe.repo.Registry
import kotlinx.serialization.json.JsonObject

/**
 * The answer to a hook call from the plugin (`POST /hook`): the whole decision lives here, so the script in the plugin only
 * forwards stdin and prints the reply. Null means "nothing to add". Every path that fails answers null: a hook never
 * stops a session.
 *
 * [rootOf] names the worktree a path is in, [callsOn] how many CodeLoupe calls that worktree has had: a session whose advice
 * is followed by none is left alone after a few pieces.
 */
class Hooks(
    private val steering: Steering,
    private val config: () -> HooksConfig,
    private val log: (String) -> Unit,
    private val rootOf: (String) -> String?,
    private val callsOn: (String) -> Int = { 0 },
) {
    private class Session {
        var given = 0
        var ignored = 0
        val seen = HashMap<String, Int>()
    }

    private val repeats = RepeatGuard()
    private val counters = HookCounters()
    private val decided = RepeatGuard(capacity = 500, ttlMs = 60_000)
    private val sessions = object : LinkedHashMap<String, Session>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Session>) = size > MAX_SESSIONS
    }

    fun stats(): HookStats = counters.snapshot()

    fun handle(json: JsonObject): JsonObject? {
        val started = System.nanoTime()
        val input = HookInput(json)
        val settings = runCatching(config).getOrDefault(HooksConfig())
        if (!settings.enabled) return pass("off", started)
        return runCatching {
            when (input.event) {
                "PreToolUse" -> steer(input, settings.steer, started)
                else -> pass("ignored", started)
            }
        }.getOrElse { pass("error", started) }
    }

    private fun steer(input: HookInput, steer: HooksConfig.SteerConfig, started: Long): JsonObject? {
        if (!steer.active) return pass("off", started)
        val cwd = input.cwd ?: return pass("ignored", started)
        val (advice, anchor) = when (val verdict = steering.judge(input.tool, input.toolInput, cwd, steer.minLines)) {
            is Verdict.Advise -> verdict.advice to verdict.anchor
            is Verdict.Skip -> return pass(verdict.reason, started)
        }
        // Two handlers for one call (a plugin and a settings file) get one answer between them.
        if (input.toolUseId != null && !decided.firstTime(input.toolUseId!!)) return pass("repeat", started)
        if (!repeats.firstTime(fingerprint(input))) return pass("repeat", started)
        val root = runCatching { rootOf(anchor) }.getOrNull()
        withheld(input.session, root, steer)?.let { return pass(it, started) }
        val deny = steer.mode == HooksConfig.REDIRECT && advice.strong
        val reply = if (deny) HookOutput.deny(AdviceText.deny(advice)) else HookOutput.context("PreToolUse", AdviceText.advise(advice))
        val nanos = System.nanoTime() - started
        if (deny) counters.denied(nanos) else counters.advised(nanos)
        runCatching { record(input, advice, deny, root, nanos) }
        return reply
    }

    // A session gets a bounded amount of advice, and stops getting it while the advice is not taken: no CodeLoupe call on the repository since.
    private fun withheld(sessionId: String, root: String?, steer: HooksConfig.SteerConfig): String? {
        val session = synchronized(sessions) { sessions.getOrPut(sessionId) { Session() } }
        synchronized(session) {
            val key = root.orEmpty()
            val now = root?.let { runCatching { callsOn(it) }.getOrDefault(0) } ?: 0
            if (session.seen.put(key, now).let { it != null && it != now }) session.ignored = 0
            if (session.given >= steer.maxPerSession) return "capped"
            if (session.ignored >= steer.giveUpAfter) return "ignored-advice"
            session.given++
            session.ignored++
        }
        return null
    }

    // The same command in the same session; the whitespace of a command does not make it another.
    private fun fingerprint(input: HookInput): String {
        val body = (input.toolInput["command"] ?: input.toolInput["file_path"])?.toString().orEmpty().replace(Regex("""\s+"""), " ")
        return "${input.session}|${input.tool}|$body"
    }

    private fun pass(reason: String, started: Long): JsonObject? {
        counters.passed(reason, System.nanoTime() - started)
        return null
    }

    private fun record(input: HookInput, advice: Advice, deny: Boolean, root: String?, nanos: Long) {
        val record = HookRecord(
            t = IsoTime.now(), hook = "steer", tool = input.tool, decision = if (deny) "denied" else "advised", why = advice.why, suggested = advice.tool,
            session = input.session.take(8), root = root, ms = nanos / 1_000 / 1000.0,
        )
        log(JsonFormat.json.encodeToString(HookRecord.serializer(), record))
    }

    companion object {
        private const val MAX_SESSIONS = 1_000

        private fun directoryOf(path: String): String = java.nio.file.Path.of(path).let { if (java.nio.file.Files.isDirectory(it)) it else it.parent ?: it }.toString()

        /** The hooks of a daemon: the index of [registry], the settings of the home's `config.json` read per call, and `hooks.jsonl`. */
        fun create(config: Config, registry: Registry, callsOn: (String) -> Int): Hooks {
            val windows = System.getProperty("os.name").lowercase().startsWith("windows")
            val paths = ShellPaths(System.getProperty("user.home").replace('\\', '/'), windows)
            return Hooks(
                Steering(IndexedSources(registry, windows), paths), { ConfigLoader.hooks(config.home) },
                AppendLog(config.home.resolve("hooks.jsonl"))::append, { path -> runCatching { registry.locate(directoryOf(path)).worktree }.getOrNull() }, callsOn,
            )
        }
    }
}
