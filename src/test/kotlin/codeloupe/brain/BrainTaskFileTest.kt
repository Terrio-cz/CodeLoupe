package codeloupe.brain

import codeloupe.TestRepos
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainTaskFileTest {
    private val sample = """
        # TER-9

        Shared state. Append only. Notation: brain/README.md

        ## 2026-10-13 10:00:52 dispatch-1
        > dispatched W1 step 1/3

        ## 2026-10-13 10:12:48 fm-ter-9-planner
        ~ one cached snapshot, TTL 5m
        -> coder: .journal/plans/TER-9.md steps 1-14
        % plan=ok

        ## 2026-10-13 11:00:38 fm-ter-9
        !P3 r1 docs drift: docs/a.md:3   <!-- anchors=@file:docs/a.md,@mod:app -->
        continued line without a marker
        % code=wip test=wip round=1
        % code=ok merge=abc123 custom=1

        ## REJECTED 2026-10-13 11:30:00 fm-ter-9-coder
        x P3 declined: used once
    """.trimIndent()

    @Test
    fun `entries carry the section's time and author, the marker and the text without the anchor comment`() {
        val notes = BrainTaskFile.parse("TER-9", sample)
        assertEquals(9, notes.entries.size)
        val first = notes.entries.first()
        assertEquals(listOf(">", "dispatched W1 step 1/3", "dispatch-1", "2026-10-13T10:00:52.000Z", "6"), listOf(first.marker, first.text, first.who, first.at, first.line.toString()))
        val handoff = notes.entries[2]
        assertEquals("->", handoff.marker)
        assertEquals("coder: .journal/plans/TER-9.md steps 1-14", handoff.text)
        val blocker = notes.entries[4]
        assertEquals("!P3", blocker.marker)
        assertEquals("r1 docs drift: docs/a.md:3", blocker.text)
        assertEquals("", notes.entries[5].marker)
        assertEquals("continued line without a marker", notes.entries[5].text)
    }

    @Test
    fun `the state is the latest value of every key, in the engine's key order, and the newest entry time is the last one`() {
        val notes = BrainTaskFile.parse("TER-9", sample)
        assertEquals(listOf("plan" to "ok", "code" to "ok", "test" to "wip", "round" to "1", "merge" to "abc123", "custom" to "1"), notes.state.toList())
        assertEquals("2026-10-13T11:30:00.000Z", notes.lastAt)
        val summary = notes.summary()
        assertEquals(9, summary.entries)
        assertEquals("TER-9", summary.id)
    }

    @Test
    fun `a reject section and an x line are rejected, others are not`() {
        val notes = BrainTaskFile.parse("TER-9", sample)
        assertEquals(listOf("x"), notes.entries.filter { it.rejected }.map { it.marker })
        assertFalse(notes.entries.first().rejected)
    }

    @Test
    fun `a file without a section has no entries and no state`() {
        val notes = BrainTaskFile.parse("TER-9", "# TER-9\n\nShared state.\n")
        assertTrue(notes.entries.isEmpty())
        assertTrue(notes.state.isEmpty())
        assertNull(notes.lastAt)
    }

    @Test
    fun `the files of a workspace are listed newest first, a malformed name or a missing file is skipped`() = runBlocking {
        val root = TestRepos.tmpDir("brain-task-files")
        val tasks = root.resolve("brain/tasks").also { it.createDirectories() }
        tasks.resolve("TER-9.md").writeText(sample)
        tasks.resolve("TER-10.md").writeText("# TER-10\n\n## 2026-10-14 08:00:00 w\n% plan=ok\n")
        tasks.resolve("notes.md").writeText("## 2026-10-15 08:00:00 w\n+ not a task\n")
        val files = BrainTaskFiles()
        assertEquals(listOf("TER-10", "TER-9"), files.summaries(root).map { it.id })
        assertEquals(listOf("plan" to "ok"), files.read(root, "TER-10")!!.state.toList())
        assertNull(files.read(root, "TER-11"))
        assertNull(files.read(root, "../TER-9"))
        assertNull(files.read(root, "notes"))
        Files.delete(tasks.resolve("TER-10.md"))
        assertEquals(listOf("TER-9"), files.summaries(root).map { it.id })
        assertEquals(emptyList(), files.summaries(root.resolve("nowhere")))
    }
}
