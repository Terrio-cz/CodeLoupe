package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.EpicProgress
import codeloupe.tracker.read.ReadyTasks
import codeloupe.tracker.read.TaskFilter
import codeloupe.tracker.read.TaskGraph
import codeloupe.tracker.read.TaskList
import codeloupe.tracker.read.WorktreeHolds
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TasksTest {
    private val store = MirrorStore(TestRepos.tmpDir("tasks").resolve("t.db"))

    init {
        val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
        runBlocking { TrackerMirror(instance, YouTrackAdapter(RecordedYouTrack()), store, 30_000).syncProject("CL", 0) }
    }

    private fun list(query: String) = TaskList.matching(store, TaskFilter.parse(query)).map { it.id }

    @Test
    fun `filters combine fields, states, negation, epics and full text`() {
        assertEquals(listOf("CL-16", "CL-17", "CL-56"), list("#resolved sort: id"))
        assertEquals(listOf("CL-26", "CL-29"), list("epic: CL-4 state: {In Progress} sort: id"))
        assertEquals(9, list("epic: CL-4 state: -{In Progress}").size)
        assertEquals(listOf("CL-1", "CL-26", "CL-29"), list("state: {In Progress}, Done #unresolved sort: id"))
        assertEquals(listOf("CL-1", "CL-4"), list("type: Epic sort: id"))
        assertTrue(list("{Fix versions}: {0.3 Context & YouTrack} #unresolved").containsAll(list("epic: CL-4")))
        assertEquals("CL-26", list("watcher polls").first())
        assertTrue(list("epic: CL-4 nonexistentword").isEmpty())
        assertEquals(listOf("CL-4", "CL-16", "CL-17", "CL-56", "CL-90", "CL-92"), list("priority: Critical sort: id"))
        assertEquals(listOf("CL-90", "CL-92"), list("epic: CL-4 sort: priority").take(2).sorted())
        assertTrue(list("{Fix versions}: 0_3%").isEmpty(), "LIKE wildcards are literal")
        assertTrue(list("see https://example.com").isEmpty(), "a URL is text, not a field")
        assertEquals(listOf("CL-26"), list("assignee: dev1 epic: CL-4 state: {In Progress} type: Feature priority: Major"))
        store.delete(listOf("CL-4"))
        assertEquals(11, list("epic: cl-4").size, "children of an epic outside the mirror")
    }

    @Test
    fun `a task is one line with state, type, priority, epic and open blockers`() {
        val text = TaskList.render(TaskList.matching(store, TaskFilter.parse("epic: CL-4 sort: id")), 3)
        assertEquals(
            """
            11 tasks, first 3:
            CL-26 In Progress · Feature · Major ‹CL-4› Local YouTrack mirror with incremental watcher
            CL-27 To do · Feature · Major ‹CL-4› issue(id): brief / sections / delta since last read
            CL-28 To do · Optimization · Normal ‹CL-4› Slim write proxy: field updates and comments return only what changed
            """.trimIndent(),
            text.lines().take(4).joinToString("\n"),
        )
        assertTrue(TaskList.render(TaskList.matching(store, TaskFilter.parse("epic: CL-4 sort: id")), 40).lines().all { it.length < 200 })
        assertContains(TaskList.render(TaskList.matching(store, TaskFilter.parse("id: CL-90")), 5), "⛔CL-26")
    }

    @Test
    fun `graph lists the epic, dependencies and dependants once each`() {
        val graph = TaskGraph.render(store, "CL-26", depth = 2, limit = 60)
        val lines = graph.lines()
        assertTrue(lines[0].startsWith("CL-26 In Progress"))
        assertTrue(lines[1].startsWith("  subtask of CL-4 "), lines[1])
        assertTrue(lines[2].startsWith("  depends on CL-56 Done"), lines[2])
        assertTrue(lines.any { it.startsWith("  is required for CL-91 ") })
        assertFalse(lines.any { it.startsWith("    ") }, "CL-56 depends on nothing, dependants of CL-26 are required for nothing")
        val chain = TaskGraph.render(store, "CL-91", depth = 2, limit = 60)
        assertTrue(chain.lines().any { it == "    depends on CL-56 Done · Feature · Critical ‹CL-1› Port CodeLoupe to Kotlin/JVM with parity to phase 1" }, chain)
        assertEquals(lines.size, lines.map { it.trim().split(' ').take(4).joinToString(" ") }.distinct().size)
        assertTrue(TaskGraph.render(store, "CL-4", depth = 1, limit = 3).endsWith("… +8 more (limit)"))
    }

    @Test
    fun `ready lists open leaf tasks whose dependencies are resolved and no worktree holds`() {
        val ready = ReadyTasks.render(store, TaskFilter.parse("epic: CL-4"), held = mapOf("CL-29" to "CL-29"), limit = 40)
        val ids = ready.lines().drop(1).takeWhile { !it.startsWith("blocked") && !it.startsWith("in worktrees") }.map { it.substringBefore(' ') }
        assertEquals(setOf("CL-26", "CL-27", "CL-28", "CL-30"), ids.toSet())
        assertContains(ready, "blocked 6: ")
        assertContains(ready, "CL-90 ⛔CL-26")
        assertContains(ready, "in worktrees 1: CL-29 (CL-29)")
        assertFalse(ReadyTasks.render(store, TaskFilter.parse(""), emptyMap(), 40).contains("CL-4 "), "an epic with open subtasks is not ready")
    }

    @Test
    fun `progress counts states and criteria and lists blockers`() {
        val progress = EpicProgress.render(store, "CL-4", limit = 40)
        assertContains(progress, "11 tasks, 0 resolved (0 %): To do 9 · In Progress 2")
        assertContains(progress, "criteria 0/27 checked; open in ")
        assertContains(progress, "11 open (6 blocked ⛔):\nCL-26 In Progress · Feature · Major Local YouTrack mirror")
        assertContains(progress, "CL-90 To do · Feature · Critical Task graph queries: tasks, task_graph, ready, epic_progress ⛔CL-26")
    }

    @Test
    fun `worktree branches mark tasks as held`() {
        val repo = TestRepos.fixtureRepo("kotlin/sample")
        TestRepos.git(repo, "worktree", "add", "-q", "-b", "feature/CL-27-brief", repo.resolveSibling("${repo.fileName}-wt").toString())
        assertEquals(mapOf("CL-27" to "feature/CL-27-brief"), WorktreeHolds.held(listOf(repo.toString(), "Z:/missing")))
    }

    @Test
    fun `every query answers from the index in well under 100 ms`() {
        repeat(3) { TaskList.matching(store, TaskFilter.parse("#unresolved watcher")) }
        val times = listOf<() -> Any>(
            { TaskList.matching(store, TaskFilter.parse("#unresolved epic: CL-4 mirror")) },
            { TaskGraph.render(store, "CL-26", 3, 60) },
            { ReadyTasks.render(store, TaskFilter.parse("epic: CL-4"), emptyMap(), 40) },
            { EpicProgress.render(store, "CL-4", 40) },
        ).map { q -> val t = System.nanoTime(); q(); (System.nanoTime() - t) / 1_000_000 }
        assertTrue(times.all { it < 100 }, times.toString())
    }
}
