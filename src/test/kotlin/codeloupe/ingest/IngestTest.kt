package codeloupe.ingest

import codeloupe.TestRepos
import codeloupe.config.BudgetsConfig
import codeloupe.metrics.Categorizer
import codeloupe.metrics.RunSummary
import codeloupe.metrics.TranscriptBuilder
import codeloupe.metrics.TranscriptBuilder.Companion.args
import codeloupe.metrics.TranscriptFile
import codeloupe.metrics.TranscriptReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IngestTest {
    private val today = Instant.parse("2026-10-08T10:00:00Z")

    private fun builder(start: Instant = today) = TranscriptBuilder(start)
        .prompt("work on TER-12")
        .turn(tools = arrayOf(Triple("a", "Read", args("file_path" to "src/Order.kt"))))
        .result("a", "x".repeat(400))
        .turn(tools = arrayOf(Triple("b", "Read", args("file_path" to "src/Order.kt")), Triple("c", "Bash", args("command" to "cd /w && git -C /w diff HEAD"))))
        .result("b", "y".repeat(40))
        .result("c", "diff")
        .turn(tools = arrayOf(Triple("d", "Edit", args("file_path" to "src/Order.kt"))))
        .result("d", "String to replace not found in the file", error = true)

    private fun session(rig: IngestRig, name: String = "s1", start: Instant = today): Path = builder(start).write(rig.project.resolve("$name.jsonl"))

    @Test
    fun `a transcript becomes a run whose figures are those of the one-shot summary`() {
        IngestRig().use { rig ->
            val file = session(rig)
            rig.ingest.passNow()

            val run = rig.runs().single()
            val summary = RunSummary.of(TranscriptReader(Categorizer(Categorizer.DEFAULT_RULES)).read(TranscriptFile(file, "session", "main")))
            assertEquals(summary.cost, run.weighted)
            assertEquals(summary.turns, run.turns)
            assertEquals(summary.peakContext, run.peakContext)
            assertEquals(summary.usage, run.usage)
            assertEquals(summary.wallSec, run.durationSec)
            assertEquals(summary.toolErrors, run.toolErrors)
            assertEquals("TER-12", run.ter)
            assertEquals("main", run.role)
            assertEquals("work on TER-12", run.title)
            val attributed = summary.byCat.values.sumOf { it.attr }
            assertEquals(Math.round(1000.0 * attributed / summary.cost).toInt(), run.share)

            val steps = rig.steps(run.id)
            assertEquals(listOf("code_read", "code_read", "git_read", "code_write"), steps.map { it.category })
            // 400 chars = 100 tokens written once to the 1 h cache (2) and read on the 2 later turns (0.1 each).
            assertEquals(800, steps[0].carried)
            assertEquals(220, steps[0].weighted)
            assertEquals("src/Order.kt", steps[0].summary)
            assertEquals("cd /w && git -C /w diff HEAD", steps[2].summary)
            assertTrue(steps[3].error)
            assertEquals("String to replace not found in the file", steps[3].errorText)
        }
    }

    @Test
    fun `a transcript that grew is read from its offset and ends as if read at once`() {
        val whole = IngestRig()
        val growing = IngestRig()
        try {
            val full = session(whole)
            whole.ingest.passNow()

            val lines = Files.readAllLines(full)
            // Cut between a tool call and its result: the pending call must survive in the saved state.
            val cut = lines.indexOfFirst { it.contains("\"tool_use\"") && it.contains("\"c\"") } + 1
            val target = growing.project.resolve("s1.jsonl")
            Files.createDirectories(target.parent)
            Files.writeString(target, lines.take(cut).joinToString("\n") + "\n")
            growing.ingest.passNow()
            assertEquals(Files.size(target), growing.scalar("SELECT offset FROM files"))
            assertEquals(2, growing.runs().single().turns)
            assertEquals(1, growing.steps(growing.runs().single().id).size, "the result of the second call has not been written yet")

            Files.copy(full, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            growing.ingest.passNow()
            assertEquals(Files.size(target), growing.scalar("SELECT offset FROM files"))

            val a = whole.runs().single()
            val b = growing.runs().single()
            assertEquals(a.copy(id = 0), b.copy(id = 0))
            assertEquals(whole.steps(a.id), growing.steps(b.id))
            assertEquals(whole.scalar("SELECT sum(cost) FROM usage_hours"), growing.scalar("SELECT sum(cost) FROM usage_hours"))

            growing.ingest.passNow()
            assertEquals(0, growing.ingest.status().filesTotal, "nothing changed: nothing is read")
        } finally {
            whole.close()
            growing.close()
        }
    }

    @Test
    fun `a last line the writer has not finished waits for its newline`() {
        IngestRig().use { rig ->
            val full = Files.readAllLines(session(rig))
            val target = rig.project.resolve("s1.jsonl")
            val half = full[2].take(full[2].length / 2)
            Files.writeString(target, full.take(2).joinToString("\n") + "\n" + half)
            rig.ingest.passNow()
            assertEquals(Files.size(target) - half.length, rig.scalar("SELECT offset FROM files"))

            Files.writeString(target, full.joinToString("\n") + "\n")
            rig.ingest.passNow()
            assertEquals(3, rig.runs().single().turns)
        }
    }

    @Test
    fun `a transcript replaced by a shorter one is read anew, not continued`() {
        IngestRig().use { rig ->
            val target = session(rig)
            rig.ingest.passNow()
            TranscriptBuilder(today).prompt("fresh start").turn().write(target)
            rig.ingest.passNow()

            val run = rig.runs().single()
            assertEquals(1, run.turns)
            assertEquals("fresh start", run.title)
            assertEquals(0, rig.steps(run.id).size)
        }
    }

    @Test
    fun `a transcript rewritten to a larger size is read anew, not continued inside a line`() {
        IngestRig().use { rig ->
            val target = session(rig)
            rig.ingest.passNow()
            val rewritten = TranscriptBuilder(today).prompt("fresh start").turn()
            repeat(6) { rewritten.turn(tools = arrayOf(Triple("r$it", "Read", args("file_path" to "a.kt")))).result("r$it", "z".repeat(300)) }
            rewritten.write(target)
            assertTrue(Files.size(target) > rig.scalar("SELECT offset FROM files"))
            rig.ingest.passNow()

            val run = rig.runs().single()
            assertEquals("fresh start", run.title)
            assertEquals(7, run.turns)
        }
    }

    @Test
    fun `a transcript rewritten to the same size is read anew`() {
        IngestRig().use { rig ->
            val target = session(rig)
            rig.ingest.passNow()
            val text = Files.readString(target)
            val edited = text.substring(0, text.lastIndexOf("not found")) + "NOT found" + text.substring(text.lastIndexOf("not found") + "not found".length)
            assertEquals(text.length, edited.length)
            Files.writeString(target, edited)
            Files.setLastModifiedTime(target, FileTime.from(Files.getLastModifiedTime(target).toInstant().plusSeconds(10)))
            rig.ingest.passNow()

            assertTrue(rig.steps(rig.runs().single().id).any { it.errorText?.contains("NOT found") == true })
        }
    }

    @Test
    fun `a database from before the tail hash gets the column and keeps resuming`() {
        val root = TestRepos.tmpDir("ingest-old")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${root.resolve("transcripts.db")}").use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE TABLE files (path TEXT PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, offset INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, project TEXT NOT NULL, session TEXT NOT NULL, ter TEXT, state TEXT)",
                )
            }
        }
        IngestRig(root).use { rig ->
            session(rig)
            rig.ingest.passNow()
            assertEquals(1, rig.runs().size)
            assertTrue(rig.db.reader.createStatement().use { s -> s.executeQuery("SELECT tail FROM files").use { it.next() && it.getString(1) != null } })
        }
    }

    @Test
    fun `subagent runs take their role and task from the meta file`() {
        IngestRig().use { rig ->
            session(rig)
            val subagents = rig.project.resolve("s1").resolve("subagents")
            TranscriptBuilder(today).prompt("review it").turn().write(subagents.resolve("agent-1.jsonl"))
            Files.writeString(subagents.resolve("agent-1.meta.json"), """{"agentType": "terrio-reviewer", "description": "review TER-77"}""")
            rig.ingest.passNow()

            val sub = rig.runs().single { it.kind == "subagent" }
            assertEquals("terrio-reviewer", sub.role)
            assertEquals("TER-77", sub.ter)
            assertEquals("s1", sub.session)
            assertEquals(listOf("main", "terrio-reviewer"), rig.queries.roles().sortedBy { if (it == "main") 0 else 1 })
        }
    }

    @Test
    fun `no summary, title or error text holds a secret that was in the transcript`() {
        IngestRig().use { rig ->
            val secrets = listOf(
                "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8", "sk-ant-" + "zZ9yY8xX7wW6vV5uU4tT3sS2rR1q", "hunter2hunter2", "Zm9vOmJhcg-secret-basic",
                "AKIA" + "ABCDEFGHIJKLMNOP", "s3cr3tPassw0rd",
            )
            TranscriptBuilder(today)
                .prompt("deploy with token ${secrets[0]} please")
                .turn(
                    tools = arrayOf(
                        Triple("a", "Bash", args("command" to "curl -H 'Authorization: Bearer ${secrets[1]}' https://x.example/api && API_KEY=${secrets[2]} ./run.sh")),
                        Triple("b", "Bash", args("command" to "psql postgres://admin:${secrets[5]}@db.example/app --password ${secrets[2]}")),
                        Triple("c", "Bash", args("command" to "export AWS_KEY=${secrets[4]}; echo done")),
                        Triple("d", "mcp__x__call", args("url" to "https://u:${secrets[3]}@host.example/path")),
                    ),
                )
                .result("a", "ok").result("b", "ok").result("c", "ok")
                .result("d", "denied token=${secrets[0]}", error = true)
                .write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()

            val run = rig.runs().single()
            val everything = rig.steps(run.id).flatMap { listOf(it.summary, it.errorText.orEmpty()) } + run.title
            secrets.forEach { secret -> assertFalse(everything.any { it.contains(secret) }, "$secret leaked into $everything") }
            assertTrue(everything.any { it.contains("curl") }, "the command stays readable around the mask")
            assertTrue(everything.all { it.length <= StepText.MAX_CHARS })
        }
    }

    @Test
    fun `the database file keeps no secret either, not in the stored inputs nor in the state it resumes from`() {
        IngestRig().use { rig ->
            val first = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
            val second = "sk-ant-" + "zZ9yY8xX7wW6vV5uU4tT3sS2rR1q"
            TranscriptBuilder(today)
                .prompt("deploy with token $first please")
                .turn(
                    tools = arrayOf(
                        Triple("a", "Bash", args("command" to "grep -rn --password $second src")),
                        Triple("b", "Bash", args("command" to "grep -rn TOKEN=$second src")),
                    ),
                )
                .result("a", "ok")
                .write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            val stored = rig.db.reader.createStatement().use { s ->
                s.executeQuery("SELECT group_concat(coalesce(input, '') || coalesce(head, ''), ' ') FROM steps").use { it.next(); it.getString(1).orEmpty() } +
                    s.executeQuery("SELECT group_concat(state, ' ') FROM files").use { it.next(); it.getString(1).orEmpty() }
            }
            assertTrue("grep" in stored, "the inputs are stored: $stored")
            assertFalse(first in stored || second in stored, stored)
        }
    }

    @Test
    fun `a long step summary is cut to 200 characters and never carries the content of an edit`() {
        IngestRig().use { rig ->
            TranscriptBuilder(today)
                .prompt("go")
                .turn(
                    tools = arrayOf(
                        Triple("a", "Bash", args("command" to "echo " + "word ".repeat(200))),
                        Triple("b", "Edit", args("file_path" to "src/A.kt", "old_string" to "OLD CONTENT", "new_string" to "NEW CONTENT")),
                    ),
                )
                .result("a", "ok").result("b", "ok")
                .write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()

            val steps = rig.steps(rig.runs().single().id)
            assertEquals(200, steps[0].summary.length)
            assertTrue(steps[0].summary.endsWith("…"))
            assertEquals("src/A.kt", steps[1].summary)
        }
    }

    @Test
    fun `a day and a run over budget each announce once, also after a restart`() {
        val root = TestRepos.tmpDir("ingest-budget")
        val budgets = BudgetsConfig(dailyWeighted = 300, runWeighted = 300)
        // The first pass reads history and stays quiet.
        IngestRig(root, budgets).use { rig ->
            session(rig, "old")
            rig.ingest.passNow()
            assertEquals(emptyList(), rig.eventTypes())
            assertEquals(2, rig.scalar("SELECT count(*) FROM breaches"))
        }
        IngestRig(root, budgets).use { rig ->
            session(rig, "new")
            rig.ingest.passNow()
            // The day was recorded on the first pass; the new run is the only news.
            assertEquals(listOf("budget.breach"), rig.eventTypes())
            assertEquals("run", rig.events.single().second["scope"]?.toString()?.trim('"'))
            rig.ingest.passNow()
            session(rig, "new")
            rig.ingest.passNow()
            assertEquals(1, rig.events.size, "the same breach is not announced again")
        }
        IngestRig(root, budgets).use { rig ->
            rig.ingest.passNow()
            assertEquals(emptyList(), rig.eventTypes(), "a restart announces nothing again")
        }
    }

    @Test
    fun `a day that goes over budget after the first pass announces once`() {
        IngestRig(budgets = BudgetsConfig(dailyWeighted = 500)).use { rig ->
            session(rig, "a")
            rig.ingest.passNow()
            assertEquals(emptyList(), rig.eventTypes(), "390 is under 500")
            session(rig, "b")
            rig.ingest.passNow()
            session(rig, "c")
            rig.ingest.passNow()
            val breaches = rig.events.filter { it.first == "budget.breach" }
            assertEquals(1, breaches.size)
            assertEquals("day", breaches.single().second["scope"]?.toString()?.trim('"'))
            assertEquals("2026-10-08", breaches.single().second["day"]?.toString()?.trim('"'))
        }
    }

    @Test
    fun `gaps of CodeLoupe calls are stored with their place, and new ones are announced after the first pass`() {
        IngestRig().use { rig ->
            rig.ingest.passNow()
            TranscriptBuilder(today)
                .prompt("go")
                .turn(tools = arrayOf(Triple("a", "mcp__codeloupe__find", args("q" to "OrderService.handle(_)"))))
                .result("a", "src/Order.kt:10-20  class OrderService")
                .turn(tools = arrayOf(Triple("b", "Bash", args("command" to "rg -n handle src"))))
                .result("b", "src/Order.kt:12")
                .turn(tools = arrayOf(Triple("c", "mcp__codeloupe__find", args("q" to "Missing"))))
                .result("c", "no declaration Missing")
                .write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()

            val gaps = rig.queries.gaps(0, null, emptyList(), 50)
            assertEquals(setOf("fallback", "empty"), gaps.map { it.kind }.toSet())
            val fallback = gaps.single { it.kind == "fallback" }
            assertEquals("rg", fallback.fallback)
            assertEquals("handle", fallback.token)
            assertEquals("s1", fallback.session)
            assertEquals(1, fallback.turn)
            assertEquals(listOf("gap.new", "gap.new"), rig.eventTypes())
            assertEquals("fallback", rig.queries.steps(rig.runs().single().id, StepSort.SEQ, 0, 10).items[0].gap)

            TranscriptBuilder(today)
                .prompt("go")
                .turn(tools = arrayOf(Triple("a", "mcp__codeloupe__find", args("q" to "OrderService.handle(_)"))))
                .result("a", "src/Order.kt:10-20  class OrderService")
                .turn(tools = arrayOf(Triple("b", "Bash", args("command" to "rg -n handle src"))))
                .result("b", "src/Order.kt:12")
                .turn(tools = arrayOf(Triple("c", "mcp__codeloupe__find", args("q" to "Missing"))))
                .result("c", "no declaration Missing")
                .turn()
                .write(rig.project.resolve("s1.jsonl"))
            rig.ingest.passNow()
            assertEquals(4, rig.runs().single().turns)
            assertEquals(2, rig.events.size, "a run that grew does not announce its old gaps again")
        }
    }

    @Test
    fun `changed category rules read the transcripts again`() {
        val root = TestRepos.tmpDir("ingest-rules")
        IngestRig(root).use { rig ->
            session(rig)
            rig.ingest.passNow()
            assertEquals("git_read", rig.steps(rig.runs().single().id)[2].category)
        }
        val rules = listOf(codeloupe.metrics.CategoryRule("mine", tool = "^Bash$")) + Categorizer.DEFAULT_RULES
        IngestRig(root, categorizer = Categorizer(rules)).use { rig ->
            rig.ingest.passNow()
            assertEquals("mine", rig.steps(rig.runs().single().id)[2].category)
            assertNotNull(rig.runs().single())
        }
    }
}
