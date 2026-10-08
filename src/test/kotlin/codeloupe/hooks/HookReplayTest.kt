package codeloupe.hooks

import codeloupe.TestRepos
import codeloupe.config.HooksConfig
import codeloupe.metrics.TranscriptFile
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HookReplayTest {
    private val repo = TestRepos.tmpDir("replay").also {
        it.resolve(".git").createDirectories()
        it.resolve("src").createDirectories()
        it.resolve("src/Thing.kt").writeText("class Thing\n")
    }
    private val cwd = repo.toString().replace("\\", "/")

    private fun use(id: String, name: String, input: String) =
        """{"type":"assistant","cwd":"$cwd","sessionId":"s1","message":{"content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]}}"""

    private fun result(id: String, text: String, totalLines: Int? = null) =
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"$id","content":"$text"}]}""" +
            (totalLines?.let { ""","toolUseResult":{"file":{"totalLines":$it}}""" } ?: "") + "}"

    private fun transcript(vararg lines: String) = TestRepos.tmpDir("replay-t").resolve("run.jsonl").also { Files.writeString(it, lines.joinToString("\n") + "\n") }

    @Test
    fun `old calls are judged like live ones, sizes taken from their own results`() {
        val file = transcript(
            use("1", "Bash", """{"command":"rg -w Thing src"}"""),
            result("1", "src/Thing.kt:1"),
            use("2", "Read", """{"file_path":"$cwd/gone/Big.kt"}"""),
            result("2", "x", totalLines = 500),
            use("3", "Read", """{"file_path":"$cwd/gone/Small.kt"}"""),
            result("3", "x", totalLines = 20),
            use("4", "Bash", """{"command":"git status"}"""),
            use("5", "Bash", """{"command":"cat $cwd/gone/Cat.kt"}"""),
            result("5", "a\\nb\\nc", totalLines = null),
            use("6", "Bash", """{"command":"rg -w Thing src"}"""),
            "not json at all",
        )
        val report = HookReplay(HooksConfig(steer = HooksConfig.SteerConfig(giveUpAfter = 100))).run(listOf(TranscriptFile(file, "session", "main")))
        assertEquals(mapOf("Bash" to 4, "Read" to 2), report.calls)
        assertEquals(2, report.advised)
        assertEquals(mapOf("search:usages -> usages" to 1, "read -> outline" to 1), report.byKind)
        assertEquals(1, report.passed["repeat"])
        assertEquals(2, report.passed["small"], report.passed.toString())
        assertEquals(mapOf("main" to ReplayReport.RoleCounts(6, 2)), report.byRole)
        assertEquals(setOf("rg", "cat"), report.programs.keys)
        assertContains(report.render(), "would be advised: 2")
    }

    @Test
    fun `an agent that never uses CodeLoupe is left alone after a few pieces of advice, one that does is not`() {
        val never = (1..10).flatMap { listOf(use("n$it", "Bash", """{"command":"rg -w Thing$it src"}"""), result("n$it", "x")) }
        val report = HookReplay().run(listOf(TranscriptFile(transcript(*never.toTypedArray()), "session", "main")))
        assertEquals(4, report.advised)
        assertEquals(6, report.passed["ignored-advice"])

        val uses = (1..10).flatMap { listOf(use("u$it", "mcp__codeloupe__find", """{"q":"X"}"""), use("m$it", "Bash", """{"command":"rg -w Thing$it src"}""")) }
        assertEquals(10, HookReplay().run(listOf(TranscriptFile(transcript(*uses.toTypedArray()), "session", "main"))).advised)
    }
}
