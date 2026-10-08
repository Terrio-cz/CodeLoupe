package codeloupe.hooks

import codeloupe.TestRepos
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HookUsageTest {
    private val home = TestRepos.tmpDir("hook-usage")
    private val start = Instant.parse("2026-10-08T10:00:00Z")

    private fun advice(offsetSec: Long, why: String, root: String, decision: String = "advised") =
        """{"t":"${start.plusSeconds(offsetSec)}","hook":"steer","tool":"Bash","decision":"$decision","why":"$why","suggested":"usages","session":"abc","root":"$root","ms":2.0}"""

    private fun call(offsetSec: Long, tool: String, root: String?, via: String = "mcp") =
        """{"t":"${start.plusSeconds(offsetSec)}","tool":"$tool","via":"$via","ms":5,"chars":100,"ok":true,"busy":false,"empty":false,"root":${root?.let { "\"$it\"" }}}"""

    @Test
    fun `advice is followed by a CodeLoupe code call on the same worktree within three minutes`() {
        Files.writeString(home.resolve("hooks.jsonl"), listOf(advice(0, "search:usages", "/r"), advice(10, "search:usages", "/r"), advice(20, "read", "/r", "denied"), advice(30, "search:usages", "/other")).joinToString("\n") + "\nnot json\n")
        Files.writeString(
            home.resolve("calls.jsonl"),
            listOf(call(5, "usages", "/r"), call(400, "usages", "/r"), call(15, "issue", "/r"), call(16, "find", "/r", via = "cli-not-counted"), call(21, "find", "/r")).joinToString("\n") + "\n",
        )
        val result = HookUsage(home).since(null)
        assertEquals(4, result.spoken)
        assertEquals(1, result.denied)
        // 0 s: followed by the call at 5 s; 10 s and the refused read at 20 s: by the find at 21 s; the one on another worktree by nothing. The call at 400 s is too late, the issue call is no code call.
        assertEquals(listOf("read -> usages" to 1, "search:usages -> usages" to 2), result.rows.map { it.kind to it.followed }.sortedBy { it.first })
        val text = result.render()
        assertContains(text, "4 pieces of advice (1 refused first)")
        assertContains(text, "180 s")
    }

    @Test
    fun `since leaves out older advice and an empty log says so`() {
        assertEquals("the steering hook has not spoken", HookUsage(home).since(null).render())
        Files.writeString(home.resolve("hooks.jsonl"), advice(0, "read", "/r") + "\n")
        assertEquals(0, HookUsage(home).since(start.plusSeconds(60)).spoken)
        assertEquals(1, HookUsage(home).since(start.minusSeconds(60)).spoken)
    }
}
