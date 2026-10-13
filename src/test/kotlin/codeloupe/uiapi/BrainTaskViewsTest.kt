package codeloupe.uiapi

import codeloupe.TestRepos
import codeloupe.brain.BrainEntry
import codeloupe.brain.BrainIndex
import codeloupe.brain.BrainSnapshot
import codeloupe.config.WorkspacesConfig
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The task notes of the brains, read from the task files of fixture workspaces. */
class BrainTaskViewsTest {
    private val first = workspace("first", "# TER-5\n\n## 2026-10-13 10:00:00 w1\n~ decided\n!P2 r1 a finding\n% plan=ok code=wip\n\n## REJECTED 2026-10-13 10:05:00 w2\nx P2 declined: used once\n")
    private val second = workspace("second", "# TER-5\n\n## 2026-10-12 09:00:00 w3\n+ a fact\n% close=ok\n")
    private val config = WorkspacesConfig(brains = listOf(first.toString(), second.toString()))

    private fun workspace(name: String, task: String) = TestRepos.tmpDir("brain-task-views").resolve(name).also {
        it.resolve("brain/tasks").createDirectories()
        it.resolve("brain/brain.mjs").writeText("")
        it.resolve("brain/tasks/TER-5.md").writeText(task)
    }

    private fun views(index: (codeloupe.brain.BrainRoot) -> BrainIndex? = { null }) = BrainTaskViews(config, index, { it == "TER-5" })

    @Test
    fun `every brain with a file for the task answers with its state and its entries, newest first`() = runBlocking {
        val brain = views().of("TER-5")
        assertEquals(listOf("first", "second"), brain.brains.map { it.workspace })
        val notes = brain.brains.first()
        assertEquals(listOf("plan" to "ok", "code" to "wip"), notes.state.map { it.key to it.value })
        assertEquals(listOf("x", "%", "!P2", "~"), notes.entries.map { it.marker })
        assertEquals(listOf(true, false, false, false), notes.entries.map { it.rejected })
        assertEquals(4, notes.entryCount)
        assertEquals("2026-10-13T10:05:00.000Z", notes.lastAt)
        assertEquals("w2", notes.entries.first().who)
        assertEquals(listOf("close" to "ok"), brain.brains.last().state.map { it.key to it.value })
    }

    @Test
    fun `a task without a file in any brain has no sections`() = runBlocking {
        assertTrue(views().of("TER-6").brains.isEmpty())
    }

    @Test
    fun `a cold index is reported as indexing, a built one marks the entries a check superseded`() = runBlocking {
        assertTrue(views().of("TER-5").brains.all { it.indexing })
        val finding = BrainEntry(where = "tasks/TER-5.md:5", text = "!P2 r1 a finding", level = "task", line = 5, task = "TER-5", superseded = true, coveredBy = "check-1")
        val built = BrainIndex(BrainSnapshot(findings = listOf(finding)))
        val notes = views { built }.of("TER-5").brains.first()
        assertFalse(notes.indexing)
        val marked = notes.entries.single { it.superseded }
        assertEquals("!P2", marked.marker)
        assertEquals("check-1", marked.coveredBy)
        assertEquals(1, notes.entries.count { it.superseded })
    }

    @Test
    fun `the task files of a workspace are listed with state and entry count, and an unknown workspace is not found`() = runBlocking {
        val id = codeloupe.brain.BrainRoot.all(config).first().id
        val tasks = views().list(id).tasks.single()
        assertEquals("TER-5", tasks.id)
        assertEquals(4, tasks.entries)
        assertTrue(tasks.tracked)
        assertEquals("plan", tasks.state.first().key)
        assertFailsWith<UiApiException> { views().list("ffffffffffff") }
        Unit
    }
}
