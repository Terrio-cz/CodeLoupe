package codeloupe.hooks

import codeloupe.TestRepos
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `plugin/hooks/hook.sh` tells the daemon which login runs a starting session, from the session's own environment (CL-226). */
class HookScriptLoginTest {
    private fun bash(): String? {
        val windows = System.getProperty("os.name").lowercase().startsWith("windows")
        val candidates = if (windows) listOf("C:/Program Files/Git/bin/bash.exe", "C:/Program Files/Git/usr/bin/bash.exe").filter { Files.exists(Path.of(it)) } else listOf("bash")
        return candidates.firstOrNull { candidate -> runCatching { ProcessBuilder(candidate, "-c", "true").start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
    }

    /** The body the daemon receives for [body] sent through the script with [env]. */
    private fun received(bash: String, body: String, env: Map<String, String?>): JsonObject {
        var got: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hook") { exchange ->
            got = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val file = Path.of(System.getProperty("codeloupe.projectDir"), "plugin", "hooks", "hook.sh").toString().replace("\\", "/")
            val process = ProcessBuilder(bash, file).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
                val e = environment()
                listOf("CLAUDE_CODE_ACCOUNT_UUID", "CODELOUPE_ACCOUNT", "CLAUDE_CODE_OAUTH_TOKEN").forEach(e::remove)
                e["CODELOUPE_PORT"] = server.address.port.toString()
                e["CODELOUPE_HOME"] = TestRepos.tmpDir("hook-login").toString()
                env.forEach { (k, v) -> if (v != null) e[k] = v }
            }.start()
            process.feed(body)
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        } finally {
            server.stop(0)
        }
        return Json.parseToJsonElement(got ?: error("nothing reached the daemon")).jsonObject
    }

    @Test
    fun `a session start carries the account uuid, the launcher's account and whether it signs in with a token, never the token`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val env = mapOf("CLAUDE_CODE_ACCOUNT_UUID" to UUID, "CODELOUPE_ACCOUNT" to "a@example.com", "CLAUDE_CODE_OAUTH_TOKEN" to "sk-secret")
        val json = received(bash!!, """{"hook_event_name":"SessionStart","session_id":"s1"}""", env)
        val login = HookInput(json).login
        assertEquals(SessionLogin(UUID, "a@example.com", token = true), login)
        assertEquals("s1", HookInput(json).session)
        assertFalse("sk-secret" in json.toString())
    }

    @Test
    fun `a subagent start carries it too, a tool call does not`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val env = mapOf("CLAUDE_CODE_ACCOUNT_UUID" to UUID)
        assertEquals(UUID, HookInput(received(bash!!, """{"hook_event_name":"SubagentStart","session_id":"s1"}""", env)).login.uuid)
        val tool = received(bash, """{"hook_event_name":"PreToolUse","tool_name":"Bash","tool_input":{"command":"echo SubagentStart"}}""", env)
        assertEquals(SessionLogin.NONE, HookInput(tool).login)
    }

    @Test
    fun `a value that is not a plain id is left out`() {
        val bash = bash()
        assumeTrue(bash != null, "no bash on this machine")
        val json = received(bash!!, """{"hook_event_name":"SessionStart","session_id":"s1"}""", mapOf("CLAUDE_CODE_ACCOUNT_UUID" to "x\",\"session_id\":\"evil", "CODELOUPE_ACCOUNT" to "a b"))
        assertEquals(SessionLogin.NONE, HookInput(json).login)
        assertEquals("s1", HookInput(json).session)
    }

    private companion object {
        const val UUID = "0b9a6c1e-1111-4222-8333-444455556666"
    }
}
