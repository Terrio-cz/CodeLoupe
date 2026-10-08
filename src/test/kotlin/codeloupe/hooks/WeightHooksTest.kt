package codeloupe.hooks

import codeloupe.TestRepos
import codeloupe.config.HooksConfig
import codeloupe.daemon.AppendLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The `UserPromptSubmit` / `Stop` hook: one line per size a session reaches, silent below the first, never in the way. */
class WeightHooksTest {
    private val home = TestRepos.tmpDir("weight-hooks")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transcript = SyntheticTranscript(home.resolve("session.jsonl"))
    private var config = HooksConfig(weight = HooksConfig.WeightConfig(warnAt = listOf(100_000, 150_000)))

    @AfterTest
    fun stop() = scope.cancel()

    private fun hooks(): Hooks = Hooks(
        Steering(FakeSources("/repo", emptyMap()), ShellPaths("/home/me", windows = false)), { config }, AppendLog(home.resolve("hooks.jsonl"))::append, { null }, { 0 },
        null, SessionWeights(scope), WarnedLevels(home.resolve("weight-warned.txt")),
    )

    private fun call(event: String, session: String = "s1", path: String? = transcript.path.toString(), extra: JsonObject = JsonObject(emptyMap())) = JsonObject(
        buildJsonObject {
            put("hook_event_name", event)
            put("session_id", session)
            if (path != null) put("transcript_path", path)
        } + extra,
    )

    private fun message(reply: JsonObject?) = reply?.get("systemMessage")?.jsonPrimitive?.content

    @Test
    fun `silent below the first size, one line at each size, from whichever event sees it first`() {
        val hooks = hooks()
        transcript.turn(40_000)
        assertNull(hooks.handle(call("UserPromptSubmit")))
        transcript.turn(99_000)
        assertNull(hooks.handle(call("Stop")))
        transcript.turn(104_000, "Read", resultChars = 90_000)
        val first = message(hooks.handle(call("Stop")))
        assertNotNull(first)
        assertContains(first, "~104k tokens")
        assertContains(first, "Read in turn 3")
        assertNull(hooks.handle(call("UserPromptSubmit")), "the same size is not announced twice")
        assertNull(hooks.handle(call("Stop")))
        transcript.turn(151_000)
        assertContains(message(hooks.handle(call("UserPromptSubmit")))!!, "~151k tokens")
        assertNull(hooks.handle(call("Stop")))
        assertEquals(2L, hooks.stats().warnings)
    }

    @Test
    fun `after a compaction the sizes count again, and another session has its own`() {
        val hooks = hooks()
        transcript.turn(120_000)
        assertNotNull(hooks.handle(call("Stop")))
        transcript.turn(30_000)
        assertNull(hooks.handle(call("Stop")), "compacted: nothing to say")
        transcript.turn(110_000)
        assertNotNull(hooks.handle(call("Stop")), "grown back past the size")
        assertNotNull(hooks.handle(call("Stop", session = "another")), "a second session on the same file is judged on its own")
    }

    @Test
    fun `a restarted daemon does not repeat a warning`() {
        transcript.turn(120_000)
        assertNotNull(hooks().handle(call("Stop")))
        assertNull(hooks().handle(call("Stop")), "a new Hooks with the same files")
    }

    @Test
    fun `it is off in each of the places, and it ignores a hook that continues a stop`() {
        transcript.turn(120_000)
        config = HooksConfig(weight = HooksConfig.WeightConfig(enabled = false))
        assertNull(hooks().handle(call("Stop")))
        config = HooksConfig(enabled = false)
        assertNull(hooks().handle(call("Stop")))
        config = HooksConfig(weight = HooksConfig.WeightConfig(warnAt = listOf(100_000, 150_000)))
        assertNull(hooks().handle(call("Stop", extra = JsonObject(mapOf("stop_hook_active" to JsonPrimitive(true))))))
        assertNotNull(hooks().handle(call("Stop")))
    }

    @Test
    fun `the sizes are configurable`() {
        transcript.turn(30_000)
        config = HooksConfig(weight = HooksConfig.WeightConfig(warnAt = listOf(20_000)))
        assertContains(message(hooks().handle(call("UserPromptSubmit")))!!, "~30k tokens")
    }

    @Test
    fun `no transcript, a missing file or a broken one is no business of the hook`() {
        val hooks = hooks()
        assertNull(hooks.handle(call("Stop", path = null)))
        assertNull(hooks.handle(call("Stop", path = home.resolve("none.jsonl").toString())))
        Files.writeString(home.resolve("junk.jsonl"), "not json\n{}\n")
        assertNull(hooks.handle(call("Stop", path = home.resolve("junk.jsonl").toString())))
        assertNull(hooks.handle(call("Stop", path = "\u0000bad")))
        assertFalse(hooks.stats().passed.isEmpty())
    }

    @Test
    fun `settings parse with sizes sorted and nonsense dropped`() {
        val parsed = HooksConfig.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"hooks":{"weight":{"warnAt":[150000,"x",-3,100000,100000],"top":99}}}""") as JsonObject)
        assertEquals(listOf(100_000, 150_000), parsed.weight.warnAt)
        assertEquals(10, parsed.weight.top)
        assertEquals(listOf(150_000, 300_000), HooksConfig.parse(JsonObject(emptyMap())).weight.warnAt)
        assertEquals(listOf(150_000, 300_000), HooksConfig.parse(kotlinx.serialization.json.Json.parseToJsonElement("""{"hooks":{"weight":{"warnAt":[]}}}""") as JsonObject).weight.warnAt)
    }
}
