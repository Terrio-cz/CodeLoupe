package codeloupe.hooks

import codeloupe.TestRepos
import codeloupe.config.HooksConfig
import codeloupe.daemon.AppendLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HooksTest {
    private val home = TestRepos.tmpDir("hooks")
    private val big = "src/main/Big.kt"
    private val sources = FakeSources("/repo", mapOf(big to 300))

    private var codeCalls = 0

    private fun hooks(config: HooksConfig = HooksConfig(), failing: Boolean = false, source: SourceFiles = FakeSources("/repo", mapOf(big to 300), failing)) =
        Hooks(Steering(source, ShellPaths("/home/me", windows = false)), { config }, AppendLog(home.resolve("hooks.jsonl"))::append, { "/repo" }, { codeCalls })

    private fun call(command: String, session: String = "s1", toolUse: String? = null, tool: String = "Bash", event: String = "PreToolUse") = buildJsonObject {
        put("hook_event_name", event)
        put("session_id", session)
        put("cwd", "/repo")
        put("tool_name", tool)
        toolUse?.let { put("tool_use_id", it) }
        put("tool_input", JsonObject(mapOf(if (tool == "Read") "file_path" to JsonPrimitive(command) else "command" to JsonPrimitive(command))))
    }

    private fun context(reply: JsonObject?) = reply?.get("hookSpecificOutput")?.jsonObject?.get("additionalContext")?.jsonPrimitive?.content

    @Test
    fun `advise mode lets the command run and tells the model the call`() {
        val reply = hooks().handle(call("rg -w OrderService"))!!
        val output = reply["hookSpecificOutput"]!!.jsonObject
        assertEquals("PreToolUse", output["hookEventName"]!!.jsonPrimitive.content)
        assertNull(output["permissionDecision"], "advice never decides about permission")
        assertContains(output["additionalContext"]!!.jsonPrimitive.content, """usages name="OrderService"""")
        assertContains(output["additionalContext"]!!.jsonPrimitive.content, "codeloupe usages OrderService")
    }

    @Test
    fun `redirect mode refuses a plain source command once and lets the same command through`() {
        val hooks = hooks(HooksConfig(steer = HooksConfig.SteerConfig(HooksConfig.REDIRECT)))
        val first = hooks.handle(call("grep -n foo /repo/$big"))!!["hookSpecificOutput"]!!.jsonObject
        assertEquals("deny", first["permissionDecision"]!!.jsonPrimitive.content)
        assertContains(first["permissionDecisionReason"]!!.jsonPrimitive.content, """grep pattern="foo"""")
        assertContains(first["permissionDecisionReason"]!!.jsonPrimitive.content, "Run the same command again")
        assertNull(hooks.handle(call("grep -n foo /repo/$big")), "the agent insisted")
        assertNull(hooks.handle(call("grep   -n foo   /repo/$big")), "whitespace does not make a new command")
        assertNotNull(hooks.handle(call("grep -n bar /repo/$big")), "another command is judged again")
    }

    @Test
    fun `redirect mode only advises when the command is not plainly about source`() {
        val hooks = hooks(HooksConfig(steer = HooksConfig.SteerConfig(HooksConfig.REDIRECT)))
        val reply = hooks.handle(call("rg OrderService"))!!["hookSpecificOutput"]!!.jsonObject
        assertNull(reply["permissionDecision"])
        assertContains(reply["additionalContext"]!!.jsonPrimitive.content, "usages")
    }

    @Test
    fun `the same command is answered once per session`() {
        val hooks = hooks()
        assertNotNull(hooks.handle(call("rg -w OrderService", session = "a")))
        assertNull(hooks.handle(call("rg -w OrderService", session = "a")))
        assertNotNull(hooks.handle(call("rg -w OrderService", session = "b")), "another session")
    }

    @Test
    fun `two handlers for one tool call get one answer`() {
        val hooks = hooks()
        assertNotNull(hooks.handle(call("rg -w OrderService", toolUse = "tu1")))
        assertNull(hooks.handle(call("rg -w OrderService", toolUse = "tu1")))
    }

    @Test
    fun `a session gets at most maxPerSession pieces of advice`() {
        val hooks = hooks(HooksConfig(steer = HooksConfig.SteerConfig(maxPerSession = 2)))
        assertNotNull(hooks.handle(call("rg -w One1")))
        assertNotNull(hooks.handle(call("rg -w Two2")))
        assertNull(hooks.handle(call("rg -w Three3")))
        assertEquals(1L, hooks.stats().passed["capped"])
    }

    @Test
    fun `advice that is not taken stops, and starts again when a CodeLoupe call is made`() {
        val hooks = hooks(HooksConfig(steer = HooksConfig.SteerConfig(giveUpAfter = 3)))
        repeat(3) { assertNotNull(hooks.handle(call("rg -w Name$it"))) }
        assertNull(hooks.handle(call("rg -w Name3")), "three pieces of advice, none taken")
        assertNull(hooks.handle(call("rg -w Name4")))
        assertEquals(2L, hooks.stats().passed["ignored-advice"])
        codeCalls++
        assertNotNull(hooks.handle(call("rg -w Name5")), "a CodeLoupe call on the repository: the agent uses it")
        assertNotNull(hooks.handle(call("rg -w Name6")))
        assertNotNull(hooks.handle(call("rg -w Name7")))
        assertNull(hooks.handle(call("rg -w Name8")))
        assertNotNull(hooks.handle(call("rg -w Name1", session = "another session")), "every session on its own")
    }

    @Test
    fun `off in either place says nothing`() {
        assertNull(hooks(HooksConfig(enabled = false)).handle(call("rg -w OrderService")))
        assertNull(hooks(HooksConfig(steer = HooksConfig.SteerConfig(HooksConfig.OFF))).handle(call("rg -w OrderService")))
    }

    @Test
    fun `calls that are no business of the hook are passed`() {
        val hooks = hooks()
        assertNull(hooks.handle(call("git status")))
        assertNull(hooks.handle(call("rg -w OrderService", event = "PostToolUse")))
        assertNull(hooks.handle(call("rg -w OrderService", tool = "Grep")))
        assertNull(hooks.handle(buildJsonObject { put("hook_event_name", "PreToolUse") }), "no tool, no cwd")
        assertNull(hooks.handle(JsonObject(emptyMap())))
    }

    @Test
    fun `the Read tool is judged on the file`() {
        val hooks = hooks()
        assertContains(context(hooks.handle(call("/repo/$big", tool = "Read")))!!, """outline target="$big"""")
    }

    @Test
    fun `an index or a configuration that fails never fails the call`() {
        assertNull(hooks(failing = true).handle(call("cat /repo/$big")))
        val broken = Hooks(Steering(sources, ShellPaths("/home/me", false)), { error("config.json unreadable") }, AppendLog(home.resolve("hooks.jsonl"))::append, { error("no root") })
        assertNotNull(broken.handle(call("cat /repo/$big")), "an unreadable configuration means the defaults")
        val throwing = Hooks(Steering(sources, ShellPaths("/home/me", false)), { HooksConfig() }, AppendLog(home.resolve("hooks.jsonl"))::append, { error("no root") })
        assertNotNull(throwing.handle(call("cat /repo/$big")), "a root that cannot be found is not needed for the answer")
    }

    @Test
    fun `a spoken hook is logged without the command and counted`() {
        val hooks = hooks()
        hooks.handle(call("rg -w SecretNameInCommand"))
        hooks.handle(call("git status"))
        val line = home.resolve("hooks.jsonl").readLines().single()
        assertTrue("SecretNameInCommand" !in line.replace("usages", ""), line)
        assertContains(line, "\"decision\":\"advised\"")
        assertContains(line, "\"suggested\":\"usages\"")
        val stats = hooks.stats()
        assertEquals(2, stats.calls)
        assertEquals(1, stats.advised)
        assertEquals(1L, stats.passed["not-a-search"])
        Files.deleteIfExists(home.resolve("hooks.jsonl"))
    }
}
