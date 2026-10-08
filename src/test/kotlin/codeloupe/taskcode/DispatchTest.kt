package codeloupe.taskcode

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.doc.DocMemory
import codeloupe.repo.Registry
import codeloupe.tools.DispatchPlanTool
import codeloupe.tools.ToolArgs
import codeloupe.tracker.RecordedYouTrack
import codeloupe.tracker.TokenSource
import codeloupe.tracker.TrackerInstance
import codeloupe.tracker.TrackerMirror
import codeloupe.tracker.TrackerSettings
import codeloupe.tracker.Trackers
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.youtrack.YouTrackAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `dispatch_plan` over the recorded CL tasks: touch sets predicted from their text, a live worktree, repeated calls. */
class DispatchTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to BILLING_TEXT, USE to USE_TEXT, ROUTES to ROUTES_TEXT))
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val registry = Registry(config, JobQueue(scope))
    private val trackers: Trackers
    private val tool: DispatchPlanTool

    init {
        val fake = RecordedYouTrack()
        fun describe(id: String, text: String) = fake.edit(id, 1_791_500_000_000L) { it["description"] = JsonPrimitive("## Context\n$text\n\n## Acceptance criteria\n- [ ] Works\n") }
        describe("CL-27", "Totals move: `Billing.total` changes in `$BILLING`.")
        describe("CL-28", "Tax rounding: `Billing.tax` in `$BILLING` is wrong.")
        describe("CL-30", "The order route `$ROUTES` gets a new path.")
        describe("CL-29", "Also `$ROUTES`.")
        val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
        val mirror = TrackerMirror(instance, YouTrackAdapter(fake), MirrorStore(TestRepos.tmpDir("dispatch").resolve("t.db")), 30_000)
        runBlocking { mirror.syncProject("CL", 0) }
        trackers = Trackers(listOf(mirror), TrackerSettings(listOf(instance)), scope)
        tool = DispatchPlanTool(trackers, DocMemory())
    }

    private fun plan(vararg args: Pair<String, JsonElement>, root: String = repo.toString()): String = runBlocking {
        tool.answer(registry, root, ToolArgs(buildJsonObject { args.forEach { (k, v) -> put(k, v) } }))
    }

    private fun ids(vararg ids: String) = "candidates" to JsonArray(ids.map(::JsonPrimitive))

    @Test
    fun `tasks naming the same file share a window with keys, the others get their own`() {
        val text = plan(ids("CL-27", "CL-28", "CL-30"))
        assertContains(text, "dispatch plan: 2 windows of 4 slots")
        assertContains(text, Regex("W1 batch CL-27\\+CL-28 · .*$BILLING"))
        assertContains(text, Regex("CL-27 light .* keys file:$BILLING dir:src/main/kotlin/demo/"))
        assertContains(text, "W2 single CL-30 · clear of the others and of the live windows")
        assertFalse("## waiting" in text, text)
    }

    @Test
    fun `a group touching a live worktree waits and names the worktree and the file`() {
        val worktree = TestRepos.tmpDir("wt").resolve("cl29")
        git(repo, "worktree", "add", "-q", "-b", "CL-29", worktree.toString())
        worktree.resolve(ROUTES).also { it.parent.createDirectories() }.writeText(ROUTES_TEXT.replace("orders", "orders2"))
        val text = plan(ids("CL-27", "CL-28", "CL-30", "CL-29"))
        assertContains(text, "W1 batch CL-27+CL-28")
        assertContains(text, Regex("CL-30 +CL-30 fights with live CL-29 \\(.*cl29\\): $ROUTES"))
        assertContains(text, Regex("CL-29 +in a worktree already \\(CL-29\\)"))
        assertContains(text, "## live\nCL-29 (")
        assertContains(text, ROUTES)
        git(repo, "worktree", "remove", "--force", worktree.toString())
    }

    @Test
    fun `a task with an unfinished dependency waits, slots cut the windows, and a repeated call is one line`() {
        val text = plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1))
        assertContains(text, "W1 single CL-27")
        assertContains(text, Regex("CL-30 +no free window \\(slots 1\\)"))
        assertContains(text, Regex("CL-91 +depends on CL-26"))
        val again = plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1))
        assertTrue(again.startsWith("dispatch:"), again)
        assertContains(again, "unchanged since your read")
        assertTrue(again.length < 250, "${again.length} chars")
        assertContains(plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1), "since" to JsonPrimitive("none")), "W1 single CL-27")
    }

    @Test
    fun `resolved tasks wait unless the plan is a replay of past work`() {
        assertContains(plan(ids("CL-16", "CL-27")), Regex("CL-16 +already resolved"))
        val replay = plan(ids("CL-16", "CL-27"), "replay" to JsonPrimitive(true))
        assertContains(replay, "CL-16")
        assertFalse("already resolved" in replay, replay)
    }

    @Test
    fun `an epic's open leaf tasks are the candidates and nothing to place says so`() {
        val text = plan("epic" to JsonPrimitive("CL-4"))
        assertContains(text, "dispatch plan: ")
        assertContains(text, "CL-26")
        assertContains(plan("query" to JsonPrimitive("project: CL state: Archived")), "nothing to place")
    }

    private companion object {
        const val BILLING = "src/main/kotlin/demo/Billing.kt"
        const val USE = "src/main/kotlin/demo/Use.kt"
        const val ROUTES = "src/main/kotlin/demo/web/Routes.kt"
        const val BILLING_TEXT = "package demo\n\nclass Billing {\n    fun total(a: Int): Int = a\n\n    fun tax(a: Int): Int = a / 10\n}\n"
        const val USE_TEXT = "package demo\n\nfun useAll(): Int = Billing().total(1)\n"
        const val ROUTES_TEXT = "package demo\n\nobject Routes {\n    const val ORDER = \"/v1/orders/{id}\"\n}\n"
    }
}
