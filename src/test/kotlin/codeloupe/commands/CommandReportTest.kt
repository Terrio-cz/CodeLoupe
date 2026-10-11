package codeloupe.commands

import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.ingest.IngestRig
import codeloupe.metrics.Categorizer
import codeloupe.metrics.PriceTable
import codeloupe.metrics.RunSummary
import codeloupe.metrics.TranscriptBuilder
import codeloupe.metrics.TranscriptBuilder.Companion.args
import codeloupe.metrics.TranscriptFile
import codeloupe.metrics.TranscriptReader
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The command report on constructed transcripts that went through the real ingest, checked against what `metrics collect` computes from the same files. */
class CommandReportTest {
    private val today = Instant.parse("2026-10-08T10:00:00Z")
    private val rg = "cd /w && rg -n \"Foo\" src"

    // What the results of session() cost held in context: 400, 80, 40, 80, 40, 50, 0, 120 and 50 characters, a quarter token each, at 2 + 0.1 per later turn of 3.
    private val carried = 220 + 44 + 21 + 42 + 21 + 25 + 0 + 60 + 25

    private fun session(model: String, start: Instant = today) = TranscriptBuilder(start, model)
        .prompt("work on TER-12")
        .turn(tools = arrayOf(Triple("a", "Read", args("file_path" to "src/A.kt")), Triple("b", "Bash", args("command" to rg))))
        .result("a", "x".repeat(400)).result("b", "y".repeat(80))
        .turn(
            tools = arrayOf(
                Triple("c", "Read", args("file_path" to "src/A.kt")), Triple("d", "Bash", args("command" to rg)),
                Triple("e", "Write", args("file_path" to "src/New.kt", "content" to "z".repeat(500))),
            ),
        )
        .result("c", "x".repeat(40)).result("d", "y".repeat(80)).result("e", "File created successfully at: src/New.kt")
        .turn(
            tools = arrayOf(
                Triple("f", "Write", args("file_path" to "src/New.kt", "content" to "w".repeat(300))),
                Triple("i", "Write", args("file_path" to "src/Old.kt", "content" to "q".repeat(100))),
                Triple("g", "Bash", args("command" to "cat > src/N.kt <<'EOF'\nclass N\nEOF")),
                Triple("h", "Bash", args("command" to "git diff --stat")),
            ),
        )
        .result("f", "The file src/New.kt has been updated successfully.").result("i", "The file src/Old.kt has been updated successfully.")
        .result("g", "").result("h", "d".repeat(120), error = true)

    private fun withRig(body: (IngestRig, Path) -> Unit) {
        val root = TestRepos.tmpDir("commands")
        try {
            IngestRig(root).use { rig -> body(rig, root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun report(rig: IngestRig, filter: CommandFilter = CommandFilter(0), top: Int = 50, prices: PriceTable = PriceTable.DEFAULT) =
        CommandAnalyzer(CommandSource(rig.db), prices) { today }.report(filter, top)

    @Test
    fun `groups carry calls, tokens, repeats, errors and the cost their results held`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        rig.ingest.passNow()
        val r = report(rig)
        val by = r.groups.associateBy { it.command }

        assertEquals(1, r.runs)
        assertEquals(9, r.calls)
        assertEquals(Math.round((400 + 80 + 40 + 80 + 40 + 50 + 0 + 120 + 50) / 4.0), r.resultTokens)
        val read = by.getValue("Read")
        assertEquals(2, read.calls)
        assertEquals(1, read.repeats, "the second read of the same file repeats the first")
        assertEquals(110, read.resultTokens)
        assertEquals(10, read.repeatTokens)
        assertEquals(220 + 21, read.weighted, "400 chars = 100 tokens, written once (2) and read on 2 later turns (0.1); 40 chars on 1")
        val search = by.getValue("rg -n <str> <arg>")
        assertEquals(2, search.calls)
        assertEquals(1, search.repeats)
        assertEquals(40, search.resultTokens)
        assertEquals(1, by.getValue("Write (new file)").calls)
        assertEquals(2, by.getValue("Write (existing file)").calls, "the answer says src/Old.kt was replaced although the run never read it")
        assertTrue(by.getValue("Write (new file)").argTokens > by.getValue("Write (existing file)").argTokens, "the 500 characters sent cost more than the 300 and 100")
        assertEquals(1, by.getValue("heredoc write").calls)
        val diff = by.getValue("git diff --stat")
        assertEquals(1, diff.errors)
        assertEquals(30, diff.resultTokens)
        assertEquals("heredoc", by.getValue("heredoc write").kind)
        assertEquals(rig.runs().single().id.toString(), read.topRuns.single().id)
        assertEquals(2, read.topRuns.single().calls)
        assertEquals("main", read.topRuns.single().role)
        assertEquals("TER-12", read.topRuns.single().ter)
    }

    @Test
    fun `the ingest stores the group of each shell call and nothing for the other tools`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        rig.ingest.passNow()
        val stored = rig.db.reader.createStatement().use { s -> s.executeQuery("SELECT group_concat(cmd, '|') FROM steps WHERE cmd IS NOT NULL").use { it.next(); it.getString(1) } }
        assertEquals("rg -n <str> <arg>|rg -n <str> <arg>|heredoc write|git diff --stat", stored)
        assertEquals(5, rig.scalar("SELECT count(*) FROM steps WHERE cmd IS NULL"), "two reads and three writes")
        assertEquals(9, rig.scalar("SELECT count(*) FROM steps WHERE in_chars IS NOT NULL AND in_hash IS NOT NULL"))
        assertEquals(3, rig.scalar("SELECT count(*) FROM steps WHERE head IS NOT NULL AND name = 'Write'"), "the answers of the writes")
    }

    @Test
    fun `the categories and the cost shares are those of metrics collect on the same transcript`() = withRig { rig, _ ->
        val file = session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        rig.ingest.passNow()
        val summary = RunSummary.of(TranscriptReader(Categorizer(Categorizer.DEFAULT_RULES)).read(TranscriptFile(file, "session", "main")))
        val r = report(rig)

        assertEquals(summary.cost, r.cost)
        assertEquals(summary.byCat.keys, r.categories.map { it.category }.toSet())
        for (c in r.categories) {
            val expected = summary.byCat.getValue(c.category)
            assertEquals(expected.calls, c.calls, c.category)
            assertEquals(expected.attr, c.weighted, c.category)
            assertEquals(expected.errors, c.errors, c.category)
            assertEquals(Math.round(expected.chars / 4.0), c.resultTokens, c.category)
        }
        assertEquals(summary.byCat.values.sumOf { it.attr }, r.weighted)
        assertEquals(r.weighted, r.groups.sumOf { it.weighted }, "every call is in exactly one group")
        assertEquals(r.categories.sumOf { it.calls }, r.groups.sumOf { it.calls })
    }

    @Test
    fun `money is the share of what the run cost at the price table, and unpriced runs are counted not guessed`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        session("claude-unknown", today.minusSeconds(3600)).write(rig.project.resolve("s2.jsonl"))
        rig.ingest.passNow()
        val priced = rig.runs().single { it.model == "claude-sonnet-5-5" }
        val r = report(rig)

        assertEquals(2, r.runs)
        assertEquals(1, r.unpricedRuns)
        val expected = PriceTable.DEFAULT.priceOf("claude-sonnet-5-5")!!.cost(priced.usage) * carried / priced.weighted
        assertNotNull(r.money)
        assertEquals(expected, r.money!!, 1e-9)
        assertEquals(r.money!!, r.groups.sumOf { it.money ?: 0.0 }, 1e-9)
        assertEquals("USD", r.currency)
        assertNull(report(rig, prices = PriceTable("none", "EUR", emptyMap())).money, "no model priced, no money")
    }

    @Test
    fun `the period, the role and one run narrow the report, and the weeks and roles are told apart`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        session("claude-sonnet-5-5", today.minusSeconds(20 * 86_400)).write(rig.project.resolve("s2.jsonl"))
        rig.ingest.passNow()
        val first = rig.runs().maxBy { it.startMs }

        val both = report(rig)
        assertEquals(2, both.runs)
        assertEquals(listOf("2026-W38", "2026-W41"), both.weeks.map { it.key })
        assertEquals(listOf("main"), both.roles.map { it.key })
        assertEquals(18, both.calls)

        assertEquals(1, report(rig, CommandFilter(today.minusSeconds(86_400).toEpochMilli())).runs)
        assertEquals(1, report(rig, CommandFilter(0, today.minusSeconds(86_400).toEpochMilli())).runs, "until")
        assertEquals(0, report(rig, CommandFilter(0, role = "coder")).runs)
        assertEquals(2, report(rig, CommandFilter(0, role = "main")).runs)
        assertEquals(1, report(rig, CommandFilter(0, run = first.id.toString())).runs)
        assertEquals(1, report(rig, CommandFilter(0, run = first.file)).runs)
        assertEquals(0, report(rig, CommandFilter(0, run = "nothing")).runs)
        assertEquals(emptyList(), report(rig, CommandFilter(0, role = "coder")).groups)
    }

    @Test
    fun `top limits the groups and the sort ranks them`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        rig.ingest.passNow()
        val analyzer = CommandAnalyzer(CommandSource(rig.db), PriceTable.DEFAULT) { today }
        val byCost = analyzer.report(CommandFilter(0), 3)
        assertEquals(3, byCost.groups.size)
        assertEquals(6, byCost.groupCount)
        assertEquals("Read", byCost.groups.first().command)
        assertEquals(listOf("Read", "rg -n <str> <arg>"), analyzer.report(CommandFilter(0), 2, CommandSort.CALLS).groups.map { it.command }, "two calls each, Read carried more")
        assertEquals("Read", analyzer.report(CommandFilter(0), 1, CommandSort.TOKENS).groups.single().command)
    }

    @Test
    fun `steps stored before the input, the answers and the groups were kept still give repeats, sizes, groups and Write states from their summaries`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        rig.ingest.passNow()
        rig.db.writer.createStatement().use { it.executeUpdate("UPDATE steps SET in_chars = NULL, in_hash = NULL, head = NULL, cmd = NULL") }
        val by = report(rig).groups.associateBy { it.command }
        assertEquals(1, by.getValue("Read").repeats)
        assertEquals(1, by.getValue("rg -n <str> <arg>").repeats)
        assertEquals(2, by.getValue("Write (new file)").calls, "without the answer a path nothing touched before is new")
        assertEquals(5, by.getValue("Write (new file)").argTokens, "the 20 characters of the two paths")
    }

    @Test
    fun `the command reads the same report from the database file, as text and as JSON`() = withRig { rig, _ ->
        session("claude-sonnet-5-5").write(rig.project.resolve("s1.jsonl"))
        rig.ingest.passNow()
        val direct = report(rig, top = 4)
        val fromFile = CommandReports.read(rig.dbFile, PriceTable.DEFAULT, CommandFilter(0), 4, CommandSort.COST, today)
        assertEquals(direct, fromFile)

        val text = CommandReportRender.text(fromFile)
        assertTrue(text.startsWith("commands 1970-01-01 .. now: 1 runs, 9 calls, 215 result tokens"), text)
        assertTrue("top 4 of 6 groups:" in text)
        assertTrue(text.lines().any { it.startsWith("Read ") && "runs 1" in it }, "a group line with its heaviest runs")
        assertTrue("by tool category (the categories of metrics collect):" in text)
        assertTrue(CommandReportRender.text(report(rig, CommandFilter(0, role = "coder"))).endsWith("no tool calls in this period"))

        val decoded = JsonFormat.json.decodeFromString(CommandReport.serializer(), JsonFormat.json.encodeToString(CommandReport.serializer(), fromFile))
        assertEquals(fromFile, decoded)
    }

    @Test
    fun `a period is a number of days or hours, a day or an instant`() {
        val now = Instant.parse("2026-10-11T12:00:00Z")
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), CommandPeriod.since("10d", now))
        assertEquals(Instant.parse("2026-10-11T00:00:00Z"), CommandPeriod.since("12h", now))
        assertEquals(Instant.parse("2026-10-05T00:00:00Z"), CommandPeriod.since("2026-10-05", now))
        assertEquals(Instant.parse("2026-10-05T08:30:00Z"), CommandPeriod.since("2026-10-05T08:30:00Z", now))
        assertTrue(runCatching { CommandPeriod.since("last week", now) }.exceptionOrNull() is IllegalArgumentException)
    }
}
