package codeloupe.taskcode

import codeloupe.TestRepos
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The files earlier tasks changed along with the touched code: model and documentation edits a short change hides. */
class CoChangeTest {
    private val store = TaskCodeStore(TestRepos.tmpDir("cochange").resolve("tasks.db"), "test")
    private var clock = 1_791_400_000L

    private fun land(task: String, vararg files: Pair<String, Char>) {
        val sha = "%040x".format(++clock)
        store.write { db ->
            store.putCommit(db, TaskCommit(sha, clock, "$task work", null, merge = false, included = true), listOf(task), files.map { (path, status) -> CommitFile(path, status, null, null) })
        }
    }

    private val service = "app/src/main/kotlin/demo/AccountService.kt"
    private val routes = "app/src/main/kotlin/demo/AccountRoutes.kt"
    private val model = "app/src/main/kotlin/demo/User.kt"
    private val docs = "docs/setup.md"

    @Test
    fun `a file that came with most earlier changes of the touched code is listed, a test or a one-off is not`() {
        land("T-1", service to 'M', model to 'M', docs to 'M')
        land("T-2", service to 'M', model to 'M', "app/src/test/kotlin/demo/AccountServiceTest.kt" to 'M')
        land("T-3", service to 'M', docs to 'M', "app/src/main/kotlin/demo/Other.kt" to 'M')
        land("T-4", service to 'M', model to 'M')
        val text = CoChange(store).render("T-9", listOf(service), known = emptyList())
        assertEquals(
            listOf("$model  ‹3 of 4 earlier tasks: T-4, T-2›", "$docs  ‹2 of 4 earlier tasks: T-3, T-1›"),
            text.lines(),
        )
        assertFalse("Other.kt" in text, "one task of four is a coincidence")
        assertFalse("Test.kt" in text, "tests are the callers section's")
    }

    @Test
    fun `files the task already names, its own earlier commits and deleted files are left out`() {
        land("T-1", service to 'M', model to 'M', docs to 'M', routes to 'D')
        land("T-2", service to 'M', model to 'M', docs to 'M', routes to 'D')
        land("T-9", service to 'M', "app/src/main/kotlin/demo/Self.kt" to 'M')
        land("T-9", service to 'M', "app/src/main/kotlin/demo/Self.kt" to 'M')
        val text = CoChange(store).render("T-9", listOf(service), known = listOf(model))
        assertEquals("$docs  ‹2 of 2 earlier tasks: T-2, T-1›", text)
    }

    @Test
    fun `tasks that changed several of the touched files together outweigh one that passed through a busy file`() {
        land("T-1", service to 'M', routes to 'M', model to 'M')
        land("T-2", service to 'M', routes to 'M', model to 'M')
        land("T-3", service to 'M', routes to 'M', model to 'M')
        (4..9).forEach { land("T-$it", service to 'M', docs to 'M') }
        val text = CoChange(store).render("T-99", listOf(service, routes), known = emptyList())
        assertContains(text, "$model  ‹3 of 3 earlier tasks")
        assertFalse(docs in text, text)
    }

    @Test
    fun `too little history or nothing touched says nothing`() {
        land("T-1", service to 'M', model to 'M')
        assertEquals("", CoChange(store).render("T-9", listOf(service), known = emptyList()))
        assertEquals("", CoChange(store).render("T-9", emptyList(), known = emptyList()))
        assertTrue(CoChange(store).render("T-9", listOf("src/Unknown.kt"), known = emptyList()).isEmpty())
    }
}
