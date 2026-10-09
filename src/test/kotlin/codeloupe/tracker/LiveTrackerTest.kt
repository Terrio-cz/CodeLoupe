package codeloupe.tracker

import codeloupe.TestRepos
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.EpicProgress
import codeloupe.tracker.read.IssueReader
import codeloupe.tracker.read.Parts
import codeloupe.tracker.read.ReadMemory
import codeloupe.tracker.read.ReadyTasks
import codeloupe.tracker.read.TaskFilter
import codeloupe.tracker.read.TaskGraph
import codeloupe.tracker.read.TaskList
import codeloupe.tracker.youtrack.JdkTransport
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Opt-in, read-only smoke test against a real tracker: `CODELOUPE_LIVE_TRACKER=<config.json with trackers>`, optionally
 * `CODELOUPE_LIVE_PROJECT` (default the first project) and `CODELOUPE_LIVE_EPIC`. Writes a report to
 * `build/reports/codeloupe/live-tracker.md`.
 */
@EnabledIfEnvironmentVariable(named = "CODELOUPE_LIVE_TRACKER", matches = ".+", disabledReason = "needs a live YouTrack project: set CODELOUPE_LIVE_TRACKER")
class LiveTrackerTest {
    @Test
    fun `a project mirrors, reads and answers queries from the index`() {
        val config = Path.of(System.getenv("CODELOUPE_LIVE_TRACKER"))
        val home = TestRepos.tmpDir("live")
        Files.copy(config, home.resolve("config.json"))
        val instance = TrackerSettingsLoader.load(home).instances.first()
        val project = System.getenv("CODELOUPE_LIVE_PROJECT") ?: instance.projects.first()
        val store = MirrorStore(home.resolve("live.db"))
        val mirror = TrackerMirror(instance.copy(projects = listOf(project)), YouTrackAdapter(JdkTransport(instance.url, instance.token)), store, 30_000)
        val report = StringBuilder("# Live tracker smoke ($project)\n\n")
        fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

        var t = System.nanoTime()
        runBlocking { mirror.syncProject(project, 0) }
        val state = store.state(project)
        assertTrue(state.issues > 0 && state.error == null, state.toString())
        report.append("- initial sync: ${state.issues} issues in ${ms(t)} ms, db ${Files.size(home.resolve("live.db")) / 1024} KB\n")
        t = System.nanoTime()
        runBlocking { mirror.syncProject(project, 0) }
        report.append("- incremental sync with no change: ${ms(t)} ms\n")

        val newest = TaskList.matching(store, TaskFilter.parse("project: $project")).first().id
        val reader = IssueReader(ReadMemory())
        val brief = runBlocking { reader.read(mirror, newest, Parts.of(null, emptyList()), null, "live") }
        val again = runBlocking { reader.read(mirror, newest, Parts.of(null, emptyList()), null, "live") }
        val full = runBlocking { reader.read(mirror, newest, Parts.of("full", emptyList()), "none", "live") }
        assertContains(again, "unchanged since")
        report.append("- $newest: brief ${brief.length} chars, full ${full.length} chars, second read ${again.length} chars\n")

        val epic = System.getenv("CODELOUPE_LIVE_EPIC") ?: TaskList.matching(store, TaskFilter.parse("project: $project type: Epic #unresolved")).firstOrNull()?.id
        val queries = listOfNotNull(
            "tasks #unresolved" to { TaskList.render(TaskList.matching(store, TaskFilter.parse("project: $project #unresolved")), 40) },
            "tasks full text" to { TaskList.render(TaskList.matching(store, TaskFilter.parse("project: $project #unresolved api")), 40) },
            epic?.let { "graph $it" to { TaskGraph.render(store, it, 2, 60) } },
            epic?.let { "ready epic: $it" to { ReadyTasks.render(store, TaskFilter.parse("epic: $it"), emptyMap(), 40) } },
            epic?.let { "progress $it" to { EpicProgress.render(store, it, 40) } },
        )
        for ((name, query) in queries) {
            query()
            t = System.nanoTime()
            val out = query()
            val took = ms(t)
            report.append("- $name: $took ms, ${out.lines().size} lines, ${out.length} chars\n")
            assertTrue(took < 100, "$name took $took ms")
        }
        Files.createDirectories(Path.of("build/reports/codeloupe"))
        Files.writeString(Path.of("build/reports/codeloupe/live-tracker.md"), report.toString())
        store.close()
    }
}
