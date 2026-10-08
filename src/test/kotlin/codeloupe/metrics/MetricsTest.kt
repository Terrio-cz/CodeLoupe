package codeloupe.metrics

import codeloupe.TestRepos
import codeloupe.config.MetricsConfig
import codeloupe.metrics.TranscriptBuilder.Companion.args
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsTest {
    private val categorizer = Categorizer(Categorizer.DEFAULT_RULES)

    private fun session(dir: Path, name: String = "s1"): Path = TranscriptBuilder()
        .prompt("work on TER-12")
        .turn(tools = arrayOf(Triple("a", "Read", args("file_path" to "src/Order.kt"))))
        .result("a", "x".repeat(400))
        .turn(tools = arrayOf(Triple("b", "Read", args("file_path" to "src/Order.kt")), Triple("c", "Bash", args("command" to "cd /w && git -C /w diff HEAD"))))
        .result("b", "y".repeat(40))
        .result("c", "diff")
        .turn(tools = arrayOf(Triple("d", "Edit", args("file_path" to "src/Order.kt"))))
        .result("d", "String to replace not found in the file", error = true)
        .write(dir.resolve("project").resolve("$name.jsonl"))

    @Test
    fun `a session becomes one run with usage, cost, categories and attributed cost`() {
        val dir = TestRepos.tmpDir("metrics-session")
        val run = TranscriptReader(categorizer).read(TranscriptFile(session(dir), "session", "main"))
        assertEquals(3, run.turns)
        assertEquals("TER-12", run.ter)
        assertEquals(Usage(input = 30, cw5m = 60, cw1h = 90, cacheRead = 300, output = 15), run.usage)
        assertEquals(30 + 75 + 180 + 30 + 75, run.usage.cost())
        assertEquals(160, run.peakContext, "peak context is the largest single turn: 10 input + 100 read + 50 written")
        assertEquals(listOf("code_read", "code_read", "git_read", "code_write"), run.tools.map { it.category })
        val first = run.tools[0]
        // 400 chars = 100 tokens, written once to the 1 h cache (2) and read on the 2 later turns (0.1 each).
        assertEquals(2, run.turns - first.turn)
        assertEquals(220, first.attr)
        assertEquals(800, first.carried)
        assertEquals("git diff", run.tools[2].cmd)
        assertTrue(run.tools[3].err)
    }

    @Test
    fun `the summary counts rereads, whole-file reads and edit errors`() {
        val dir = TestRepos.tmpDir("metrics-summary")
        val summary = RunSummary.of(TranscriptReader(categorizer).read(TranscriptFile(session(dir), "session", "main")))
        assertEquals(CodeReads(calls = 2, files = 1, rereads = 1, whole = 2, chars = 440), summary.codeRead)
        assertEquals(CodeEdits(calls = 1, errors = 1, errSamples = listOf("String to replace not found in the file")), summary.codeEdit)
        assertEquals(CmdStats(1, summary.byCat.getValue("git_read").attr), summary.cmds["git_read | git diff"])
        assertEquals(1, summary.toolErrors)
    }

    @Test
    fun `subagent runs take role and task from their meta file, sessions are main`() {
        val dir = TestRepos.tmpDir("metrics-finder")
        session(dir)
        val subagents = dir.resolve("project").resolve("s1").resolve("subagents")
        TranscriptBuilder().prompt("review").turn().write(subagents.resolve("agent-1.jsonl"))
        Files.writeString(subagents.resolve("agent-1.meta.json"), """{"agentType": "terrio-reviewer", "description": "review TER-77"}""")
        val report = MetricsCollector(categorizer) { Instant.parse("2026-10-08T00:00:00Z") }
            .collect(listOf(dir.resolve("project")), "test", Instant.parse("2026-01-01T00:00:00Z"), null)
        assertEquals(setOf("main", "terrio-reviewer"), report.aggregate.keys)
        assertEquals("TER-77", report.runs.single { it.role == "terrio-reviewer" }.ter)
        assertEquals("subagent", report.runs.single { it.role == "terrio-reviewer" }.kind)
        assertEquals(2, report.runs.size)
        assertEquals(1, report.aggregate.getValue("main").runs)
    }

    @Test
    fun `runs outside the period are left out`() {
        val dir = TestRepos.tmpDir("metrics-period")
        session(dir)
        val collector = MetricsCollector(categorizer)
        assertEquals(0, collector.collect(listOf(dir.resolve("project")), "late", null, Instant.parse("2026-10-01T00:00:00Z")).runs.size)
        assertEquals(1, collector.collect(listOf(dir.resolve("project")), "ok", null, Instant.parse("2026-12-01T00:00:00Z")).runs.size)
    }

    @Test
    fun `medians, upper quartile and compare`() {
        assertEquals(Pick(0, 0, 0), Pick.of(emptyList()))
        assertEquals(Pick(3, 4, 15), Pick.of(listOf(5, 1, 4, 3, 2)))
        assertEquals(Pick(3, 4, 10), Pick.of(listOf(1, 2, 4, 3)), "an even count takes the rounded mean of the middle two")
        val dir = TestRepos.tmpDir("metrics-compare")
        session(dir)
        val a = MetricsCollector(categorizer).collect(listOf(dir.resolve("project")), "a", null, null).aggregate
        val compared = MetricsRender.compare(a, a)
        assertTrue(compared.startsWith("main  runs 1 -> 1\n   cost 390→390 (+0%)  peakContext 160→160 (+0%)  turns 3→3 (+0%)"), compared)
        assertContains(compared, "codeReadChars 440→440 (+0%)")
        assertContains(compared, "codeEditErrors 1→1 (+0%)")
        assertContains(MetricsRender.summary(a), "main  runs 1  cost med 390 p75 390 Σ 390  peakCtx med 160  turns med 3")
        assertContains(MetricsRender.summary(a, setOf("main")), "git diff")
        assertEquals("", MetricsRender.summary(a, setOf("nobody")))
    }

    @Test
    fun `shell commands are reduced to what they run`() {
        assertEquals("git diff", CommandKey.of("cd /w && git -C /w diff HEAD"))
        assertEquals("node terrio.mjs api", CommandKey.of("cd X; node run/terrio.mjs api GET /x"))
        assertEquals("node brain.mjs fact", CommandKey.of("""node C:\Users\me\terrio\brain\brain.mjs fact add"""))
        assertEquals("git", CommandKey.of("""JAVA_HOME=C:/jdk git "status""""))
        assertEquals("ls", CommandKey.of("""ls C:\Users\me\terrio\brain\facts"""))
        assertEquals("rg", CommandKey.of("rg -n foo src"))
        assertEquals("", CommandKey.of(null))
    }

    @Test
    fun `configured categories come first and can replace the built-in ones`() {
        val file = Json.parseToJsonElement(
            """{"metrics": {"transcriptDirs": ["/t"], "categories": [{"category": "gradle", "tool": "^Bash${'$'}", "command": "gradlew"}, {"category": "bad", "tool": "("}, {"tool": "x"}]}}""",
        ).jsonObject
        val config = MetricsConfig.parse(file)
        assertEquals(listOf("/t"), config.transcriptDirs)
        assertEquals(1, config.categories.size, "a rule without a category or with a broken pattern is dropped")
        val custom = Categorizer(config.categories + Categorizer.DEFAULT_RULES)
        assertEquals("gradle", custom.categorize("Bash", args("command" to "./gradlew test")))
        assertEquals("build_test", categorizer.categorize("Bash", args("command" to "./gradlew test")))
        assertEquals("shell_other", custom.categorize("Bash", args("command" to "echo hi")))
        assertEquals("other", Categorizer(config.categories).categorize("Bash", args("command" to "echo hi")))
        assertEquals(MetricsConfig(), MetricsConfig.parse(Json.parseToJsonElement("{}").jsonObject))
    }

    @Test
    fun `code files are told from other files by extension, any case`() {
        assertEquals("code_read", categorizer.categorize("Read", args("file_path" to "A.KT")))
        assertEquals("doc_read", categorizer.categorize("Read", args("file_path" to "README.md")))
        assertEquals("code_write", categorizer.categorize("Write", args("file_path" to "build.gradle.kts")))
        assertEquals("other_write", categorizer.categorize("Edit", args("file_path" to "notes.md")))
        assertEquals("codeloupe", categorizer.categorize("mcp__codeloupe__find", args("q" to "x")))
        assertEquals("codeloupe", categorizer.categorize("Bash", args("command" to "codeloupe usages Foo")))
    }
}
