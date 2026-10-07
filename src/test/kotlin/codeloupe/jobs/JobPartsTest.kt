package codeloupe.jobs

import codeloupe.TestRepos
import codeloupe.events.Scrubber
import codeloupe.jobs.PolicyDecision.Verdict
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JobPartsTest {
    @Test
    fun `summaries read test counts of common runners, failure lines and the tail`() {
        fun summary(text: String) = SummaryReader.read(TestRepos.tmpDir("log").resolve("j.log").also { Files.writeString(it, text) })
        val gradle = summary("> Task :test\n\u001B[31mOrderTest > total() FAILED\u001B[0m\n    AssertionError at OrderTest.kt:12\n\n412 tests completed, 2 failed, 3 skipped\nBUILD FAILED in 1m 3s\n")
        assertEquals(listOf(412, 407, 2, 3), listOf(gradle.tests, gradle.passed, gradle.failed, gradle.skipped))
        assertEquals(listOf("OrderTest > total() FAILED", "BUILD FAILED in 1m 3s"), gradle.failures)
        assertEquals("BUILD FAILED in 1m 3s", gradle.tail.last())
        assertEquals(listOf(10, 1), summary("Tests run: 10, Failures: 1, Errors: 0, Skipped: 0").let { listOf(it.tests, it.failed) })
        assertEquals(listOf(7, 2, 1), summary("# tests 7\n# pass 4\n# fail 2\n# skipped 1\n").let { listOf(it.tests, it.failed, it.skipped) })
        assertEquals(listOf(9, 1), summary("Tests:       1 failed, 8 passed, 9 total").let { listOf(it.tests, it.failed) })
        assertEquals(listOf(6, 1), summary("===== 1 failed, 5 passed in 0.31s =====").let { listOf(it.tests, it.failed) })
        assertEquals(null, summary("BUILD SUCCESSFUL").tests)
        val long = summary((1..40).joinToString("\n") { "line $it" })
        assertEquals((26..40).map { "line $it" }, long.tail)
    }

    @Test
    fun `command lines quote for Bash and split back`() {
        val argv = listOf("node", "a b", "it's", "", "\$HOME", "x;y", "--flag=1")
        val line = CommandLine.join(argv)
        assertEquals("node 'a b' 'it'\\''s' '' '\$HOME' 'x;y' --flag=1", line)
        assertEquals(argv, CommandLine.split(line))
        assertEquals(listOf("a", "b c", "d\"e", "f\\g"), CommandLine.split("a \"b c\" 'd\"e' f\\\\g"))
        assertEquals("K='v w' cmd", CommandLine.withEnv(mapOf("K" to "v w"), listOf("cmd")))
        assertFailsWith<IllegalArgumentException> { CommandLine.split("echo 'open") }
    }

    @Test
    fun `actions are a closed set of typed kinds with optional conditions`() {
        val job = assertIs<Action.RunJob>(ActionParser.parse("job@gradle-test:./gradlew test --tests 'Foo Bar'"))
        assertEquals(listOf("./gradlew", "test", "--tests", "Foo Bar"), job.command)
        assertEquals("gradle-test", job.slot)
        val notify = assertIs<Action.Notify>(ActionParser.parse("failed>0 && tests>=10 ? notify:tests failed"))
        assertEquals("tests failed", notify.message)
        val record = JobRecord(
            id = "J", rootId = "J", command = "x", cwd = "/", status = JobStatus.DONE, exit = 1, createdAt = "", log = "", wakeOn = Wake.ALWAYS,
            summary = JobSummary(tests = 12, failed = 2),
        )
        assertTrue(notify.condition!!.holds(record))
        assertFalse(Condition.parse("failed==0").holds(record))
        assertFalse(Condition.parse("skipped==0").holds(record), "a count the log did not report never matches")
        assertIs<Action.Webhook>(ActionParser.parse("webhook:http://127.0.0.1:9000/x"))
        for (bad in listOf("rm -rf /", "shell:rm -rf /", "notify@x:y", "job:", "webhook:", "nope==1 ? notify", "exit=1 ? notify")) {
            assertFailsWith<IllegalArgumentException>(bad) { ActionParser.parse(bad) }
        }
    }

    @Test
    fun `hook answers are read like Claude Code reads them, but failing closed`() {
        val hook = PolicyHook(listOf("unused"), 1000)
        fun verdict(exit: Int, out: String, err: String = "") = hook.decide(exit, out, err).verdict
        assertEquals(Verdict.ALLOW, verdict(0, ""))
        assertEquals(Verdict.DENY, verdict(0, """{"hookSpecificOutput":{"permissionDecision":"deny","permissionDecisionReason":"no"}}"""))
        assertEquals(Verdict.ASK, verdict(0, """{"hookSpecificOutput":{"permissionDecision":"ask"}}"""))
        assertEquals(Verdict.ALLOW, verdict(0, """{"hookSpecificOutput":{"permissionDecision":"allow"}}"""))
        assertEquals(Verdict.DENY, verdict(0, """{"decision":"block","reason":"legacy"}"""))
        assertEquals(Verdict.DENY, verdict(0, """{"continue":false}"""))
        assertEquals(Verdict.DENY, verdict(2, "", "blocked"))
        assertEquals(Verdict.DENY, verdict(1, ""), "a crashing hook denies (Claude Code would let it through)")
        assertEquals(Verdict.DENY, verdict(0, "not json"))
        assertEquals(Verdict.DENY, verdict(0, """{"hookSpecificOutput":{"permissionDecision":"maybe"}}"""))
        assertEquals(Verdict.ALLOW, PolicyHook(null, 1000).check("anything", "/").verdict)
        assertEquals(Verdict.DENY, PolicyHook(listOf("no-such-program-xyz"), 1000).check("ls", "/").verdict)
    }

    @Test
    fun `a later step's condition is judged against the job before it, not the first one`() {
        val steps = listOf("job:./gradlew build", "failed==0 ? job:deploy", "notify").map(ActionParser::parse)
        val build = record(JobSummary())
        val first = StepPlan.of(steps, build, startJobs = true)
        assertEquals(listOf("./gradlew", "build"), first.next!!.command)
        assertEquals(2, first.rest.size, "the deploy step is passed on unjudged")
        val tests = record(JobSummary(tests = 4, failed = 0))
        assertEquals(listOf("deploy"), StepPlan.of(first.rest, tests, startJobs = true).next!!.command)
        val cancelled = StepPlan.of(steps, build, startJobs = false)
        assertEquals(null, cancelled.next)
        assertEquals(1, cancelled.now.size, "a cancelled job starts no job, notifies still run")
    }

    @Test
    fun `slow hooks and endless lines are cut off`() {
        val hook = PolicyHook(FakePolicyHook.command(TestRepos.tmpDir("hook").resolve("calls")), 3000)
        val began = System.nanoTime()
        val decision = hook.check("slow-me", TestRepos.tmpDir("cwd").toString())
        assertEquals(Verdict.DENY, decision.verdict)
        assertTrue(System.nanoTime() - began < 20_000_000_000, decision.reason)
        val log = TestRepos.tmpDir("log").resolve("j.log").also { Files.writeString(it, "x".repeat(2_000_000) + "\nlast\n") }
        assertEquals(listOf(200, 4), SummaryReader.read(log).tail.map { it.length })
    }

    @Test
    fun `program names resolve like Bash - bare names on PATH only - and read as programs`() {
        val dir = TestRepos.tmpDir("exe")
        Files.writeString(dir.resolve("tool.cmd"), "@echo off")
        val env = mapOf("PATH" to TestRepos.tmpDir("empty").toString(), "PATHEXT" to ".EXE;.CMD")
        assertEquals("tool", Executables.resolve("tool", dir, env, windows = true), "never from the job's directory")
        assertEquals(dir.resolve("tool.cmd").toString(), Executables.resolve("./tool", dir, env, windows = true))
        assertEquals("'X=1' status", CommandLine.join(listOf("X=1", "status")))
        assertEquals("git --x=1", CommandLine.join(listOf("git", "--x=1")))
    }

    private fun record(summary: JobSummary) = JobRecord(
        id = "J", rootId = "J", command = "x", cwd = "/", status = JobStatus.DONE, exit = 0, createdAt = "", log = "", wakeOn = Wake.ALWAYS,
        summary = summary,
    )

    @Test
    fun `the scrubber masks secret-looking values but keeps the rest`() {
        assertEquals("curl -H 'Authorization: ***' https://***@host/x", Scrubber.text("curl -H 'Authorization: Bearer abcdefghijk' https://u:p4ss@host/x"))
        assertEquals("deploy --api-key=*** --region eu", Scrubber.text("deploy --api-key=abc123 --region eu"))
        assertEquals("DB_PASSWORD=*** ./run", Scrubber.text("DB_PASSWORD=hunter2 ./run"))
        assertEquals("token ***", Scrubber.text("token perm:YWJjZGVmZ2hpams=.NDMtMQ==.xyz"))
        assertEquals("./gradlew test --tests OrderTest", Scrubber.text("./gradlew test --tests OrderTest"))
    }
}
