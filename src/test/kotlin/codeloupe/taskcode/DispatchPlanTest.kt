package codeloupe.taskcode

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The grouping rules of `dispatch_plan` on touch sets alone: no two windows share code, a group is never split. */
class DispatchPlanTest {
    private fun task(id: String, vararg files: String, rank: Int = 3, light: Boolean = false, blockers: Set<String> = emptySet()) =
        DispatchPlan.Task(id, "Task $id", "P$rank", rank, light, blockers, files.associateWith { '=' })

    private fun plan(tasks: List<DispatchPlan.Task>, live: List<DispatchPlan.Live> = emptyList(), slots: Int = 4, width: DispatchPlan.Width = DispatchPlan.Width.DIR) =
        DispatchPlan(width).plan(tasks, live, slots)

    private fun DispatchPlan.Result.shape() = windows.map { it.kind + " " + it.steps.joinToString(">") { s -> s.joinToString("+") } }

    @Test
    fun `tasks that share a file are one window and say which file`() {
        val result = plan(listOf(task("T-1", "a/X.kt", "a/Y.kt"), task("T-2", "a/X.kt", "b/Z.kt"), task("T-3", "c/W.kt")))
        assertEquals(listOf("chain T-1>T-2", "single T-3"), result.shape())
        assertContains(result.windows.first().why.single(), "a/X.kt (T-1 =, T-2 =)")
    }

    @Test
    fun `up to three light tasks that clash are one batch, a heavier one makes a chain`() {
        val batch = plan(listOf(task("T-1", "a/X.kt", light = true), task("T-2", "a/X.kt", light = true), task("T-3", "a/X.kt", light = true)))
        assertEquals(listOf("batch T-1+T-2+T-3"), batch.shape())
        val chain = plan(listOf(task("T-1", "a/X.kt", light = true), task("T-2", "a/X.kt")))
        assertEquals(listOf("chain T-1>T-2"), chain.shape())
        val four = plan((1..4).map { task("T-$it", "a/X.kt", light = true) })
        assertEquals(listOf("chain T-1>T-2>T-3"), four.shape())
        assertEquals("after the chain T-1→T-2→T-3", four.waiting.single { it.id == "T-4" }.reason)
    }

    @Test
    fun `a shared directory clashes unless the width is file`() {
        val tasks = listOf(task("T-1", "a/X.kt"), task("T-2", "a/Y.kt"))
        assertEquals(listOf("chain T-1>T-2"), plan(tasks).shape())
        assertContains(plan(tasks).windows.single().why.single(), "a (T-1 =, T-2 =)")
        assertEquals(listOf("single T-1", "single T-2"), plan(tasks, width = DispatchPlan.Width.FILE).shape())
    }

    @Test
    fun `windows are ranked by urgency and cut at the slots`() {
        val tasks = listOf(task("T-1", "a/X.kt", rank = 5), task("T-2", "b/Y.kt", rank = 1), task("T-3", "c/Z.kt", rank = 3))
        assertEquals(listOf("single T-2", "single T-3"), plan(tasks, slots = 2).shape())
        assertEquals("no free window (slots 2)", plan(tasks, slots = 2).waiting.single().reason)
        assertTrue(plan(tasks, slots = 0).windows.isEmpty())
    }

    @Test
    fun `a group that touches a live worktree waits whole and cites it`() {
        val tasks = listOf(task("T-1", "a/X.kt"), task("T-2", "a/X.kt"), task("T-3", "z/Q.kt"))
        val result = plan(tasks, live = listOf(DispatchPlan.Live("T-9 (C:/wt/T-9)", setOf("a/X.kt"))))
        assertEquals(listOf("single T-3"), result.shape())
        assertEquals(listOf("T-1", "T-2"), result.waiting.map { it.id })
        assertContains(result.waiting.first().reason, "T-1 fights with live T-9 (C:/wt/T-9): a/X.kt")
        assertContains(result.waiting.last().reason, "grouped: T-1+T-2")
    }

    @Test
    fun `dependencies chain the tasks, an unfinished outside dependency waits, and so does what depends on it`() {
        val tasks = listOf(task("T-2", "b/B.kt", blockers = setOf("T-1")), task("T-1", "a/A.kt"), task("T-3", "c/C.kt", blockers = setOf("T-8")), task("T-4", "d/D.kt", blockers = setOf("T-3")))
        val result = plan(tasks)
        assertEquals(listOf("chain T-1>T-2"), result.shape())
        assertContains(result.windows.single().why.single(), "T-2 depends on T-1")
        assertEquals(listOf("T-3", "T-4"), result.waiting.map { it.id })
        assertContains(result.waiting.first().reason, "depends on T-8")
        val unknown = plan(listOf(task("T-5", "e/E.kt", blockers = setOf("T-7?"))))
        assertContains(unknown.waiting.single().reason, "depends on T-7")
    }

    @Test
    fun `a task whose text names no code is unproven and markdown never blocks`() {
        val result = plan(listOf(task("T-1"), task("T-2", "docs/notes.md"), task("T-3", "docs/notes.md", "a/X.kt")))
        assertEquals(listOf("single T-1", "single T-2", "single T-3"), result.shape())
        assertEquals(listOf("T-1", "T-2"), result.unproven)
    }
}
