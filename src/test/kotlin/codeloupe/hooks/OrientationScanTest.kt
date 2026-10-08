package codeloupe.hooks

import codeloupe.TestRepos
import codeloupe.metrics.TranscriptFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class OrientationScanTest {
    private fun turn(id: String, name: String, input: String) =
        """{"type":"assistant","message":{"id":"$id","content":[{"type":"tool_use","id":"u$id","name":"$name","input":$input}]}}"""

    private fun transcript(vararg lines: String) = TestRepos.tmpDir("orient").resolve("s.jsonl").also { Files.writeString(it, lines.joinToString("\n") + "\n") }

    @Test
    fun `orientation calls of the first turns are counted apart for sessions that began with the context`() {
        val plain = transcript(
            turn("m1", "Bash", """{"command":"ls -la"}"""),
            turn("m2", "Glob", """{"pattern":"**/*.kt"}"""),
            turn("m3", "Bash", """{"command":"cd src && find . -name '*.kt'"}"""),
            turn("m4", "Bash", """{"command":"git status"}"""),
            "not json",
        )
        val hooked = transcript(
            """{"type":"attachment","attachment":{"content":"CodeLoupe orientation for repo: branch main"}}""",
            turn("n1", "Read", """{"file_path":"/x/A.kt"}"""),
            *(2..10).map { turn("n$it", "Bash", """{"command":"ls"}""") }.toTypedArray(),
        )
        val quiet = transcript(turn("q1", "Bash", """{"command":"git status"}"""))
        val subagent = transcript(turn("a1", "Bash", """{"command":"ls"}"""))
        val report = OrientationScan().run(
            listOf(
                TranscriptFile(plain, "session", "main"), TranscriptFile(hooked, "session", "main"), TranscriptFile(quiet, "session", "main"),
                TranscriptFile(subagent, "subagent", "terrio-coder"),
            ),
        )
        assertEquals(2, report.withoutHook.sessions)
        assertEquals(3, report.withoutHook.calls)
        assertEquals(1, report.withoutHook.withAny)
        assertEquals(mapOf("Glob" to 1, "find" to 1, "ls" to 1), report.withoutHook.byKind)
        assertEquals(1, report.withHook.sessions)
        assertEquals(7, report.withHook.calls, "turns 2 to 8 only")
        assertContains(report.render(), "with the hook's context")
    }
}
