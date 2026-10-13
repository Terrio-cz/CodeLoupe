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
import codeloupe.tracker.IssueLink
import codeloupe.tracker.LinkKind
import codeloupe.tracker.TrackerInstance
import codeloupe.tracker.TrackerIssue
import codeloupe.tracker.TrackerMirror
import codeloupe.tracker.TrackerSettings
import codeloupe.tracker.Trackers
import codeloupe.tracker.mirror.MirrorStore
import codeloupe.tracker.read.TaskRows
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
        assertContains(text, Regex("CL-27 light \\S+ \\S+ .* keys file:$BILLING dir:src/main/kotlin/demo/"))
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
        assertContains(text, Regex("CL-30 \\(.+\\) +CL-30 fights with live CL-29 \\(.*cl29\\): $ROUTES"))
        assertContains(text, Regex("CL-29 \\(.+\\) +in a worktree already \\(CL-29\\)"))
        assertContains(text, "## live\nCL-29 (")
        assertContains(text, ROUTES)
        git(repo, "worktree", "remove", "--force", worktree.toString())
    }

    @Test
    fun `a task with an unfinished dependency waits, slots cut the windows, and a repeated call is one line`() {
        val text = plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1))
        assertContains(text, "W1 single CL-27")
        assertContains(text, Regex("CL-30 \\(.+\\) +no free window \\(slots 1\\)"))
        assertContains(text, Regex("CL-91 \\(.+\\) +depends on CL-26"))
        val again = plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1))
        assertTrue(again.startsWith("dispatch:"), again)
        assertContains(again, "unchanged since your read")
        assertTrue(again.length < 250, "${again.length} chars")
        assertContains(plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1), "since" to JsonPrimitive("none")), "W1 single CL-27")
    }

    @Test
    fun `window and waiting rows carry the task state and the window rows the epic`() {
        fun row(id: String) = trackers.mirror(id)!!.let { (mirror, canonical) -> TaskRows.row(mirror.store, canonical)!! }
        val text = plan(ids("CL-27", "CL-30", "CL-91"), "slots" to JsonPrimitive(1))
        val first = row("CL-27")
        assertContains(text, Regex("CL-27 \\S+ \\S+ ${Regex.escape(first.state!!)}( ‹${first.parent}›)? "))
        assertContains(text, "CL-30 (${row("CL-30").state})  no free window")
        assertContains(text, "CL-91 (${row("CL-91").state})  depends on")
    }

    @Test
    fun `resolved tasks wait unless the plan is a replay of past work`() {
        assertContains(plan(ids("CL-16", "CL-27")), Regex("CL-16 \\(.+\\) +already resolved"))
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

    // A state shaped like the dispatcher run that planned nothing: a workspace of project WS whose most urgent tasks are all taken, in progress,
    // blocked or foreign, with 2 windows live. Rebuilt as a fixture: the mirror of that moment was not kept, so it cannot be replayed itself.
    private class Pool(val tool: DispatchPlanTool, val workspace: Path)

    private fun issue(id: String, rank: Int, state: String, dependsOn: String? = null) = TrackerIssue(
        id = id, project = id.substringBefore('-'), summary = "Task $id", description = "## Context\nNo code is named here.\n\n## Acceptance criteria\n- [ ] Works\n",
        created = 1_791_000_000_000L, updated = 1_791_000_000_000L + id.substringAfter('-').toLong(), state = state, type = "Task", priority = "P$rank", priorityRank = rank,
        links = listOfNotNull(dependsOn?.let { IssueLink(LinkKind.DEPENDS_ON, "depends on", it) }),
    )

    private fun pool(scoped: Boolean = true): Pool {
        val inProgress = (1..4).map { issue("WS-$it", 0, "In Progress") }
        val blocked = (5..8).map { issue("WS-$it", 0, "To do", dependsOn = "WS-900") }
        val worktreeHeld = (9..10).map { issue("WS-$it", 0, "To do") }
        val excluded = listOf(issue("WS-11", 0, "To do"))
        val foreign = (1..20).map { issue("XY-$it", 0, "To do") }
        val free = (20..39).map { issue("WS-$it", 2, "To do") }
        val dependency = issue("WS-900", 3, "To do")
        val instance = TrackerInstance("pool", "youtrack", "https://pool.example", listOf("WS", "XY"), TokenSource.Env("UNUSED") { emptyMap() })
        val mirror = TrackerMirror(instance, YouTrackAdapter(RecordedYouTrack()), MirrorStore(TestRepos.tmpDir("pool").resolve("p.db")), 3_600_000)
        mirror.store.upsert(inProgress + blocked + worktreeHeld + excluded + foreign + free + dependency, System.currentTimeMillis())
        listOf("WS", "XY").forEach { mirror.store.markSynced(it, System.currentTimeMillis(), null, checked = true) }
        val pooled = Trackers(listOf(mirror), TrackerSettings(listOf(instance)), scope)
        val workspace = TestRepos.tmpDir("workspace")
        val scopes = if (scoped) DispatchScopes { DispatchScope(listOf("WS"), repo.toString()) } else DispatchScopes.NONE
        return Pool(DispatchPlanTool(pooled, DocMemory(), scopes = scopes), workspace)
    }

    private fun Pool.plan(vararg args: Pair<String, JsonElement>, root: Path = workspace): String = runBlocking {
        tool.answer(registry, root.toString(), ToolArgs(buildJsonObject { args.forEach { (k, v) -> put(k, v) } }))
    }

    private inline fun <T> withWorktrees(vararg branches: String, body: () -> T): T {
        val dirs = branches.map { branch -> TestRepos.tmpDir("wt").resolve(branch.lowercase()).also { git(repo, "worktree", "add", "-q", "-b", branch, it.toString()) } }
        try {
            return body()
        } finally {
            dirs.forEach { git(repo, "worktree", "remove", "--force", it.toString()) }
            branches.forEach { git(repo, "branch", "-D", it) }
        }
    }

    @Test
    fun `a pool filled with taken, blocked and foreign rows still yields the plannable tasks`() = withWorktrees("WS-9", "WS-10") {
        val text = pool().plan("slots" to JsonPrimitive(4), "exclude" to JsonArray(listOf(JsonPrimitive("ws-11"))))
        // The 4 free windows go to plannable tasks, which come after 4 + 4 + 2 + 1 + 20 rows that could not be planned.
        assertContains(text, "dispatch plan: 4 windows of 4 slots")
        assertContains(text, "W1 single WS-24 ")
        val windows = text.substringAfter("## windows").substringBefore("## waiting")
        listOf("WS-1 ", "WS-5 ", "WS-9 ", "WS-10 ", "WS-11 ", "XY-").forEach { assertFalse(it in windows, "$it in $windows") }
        assertContains(text, "left out of the pool: 1 left out by the caller, 6 in a worktree or in progress/review, 4 waiting on a dependency")
        text.substringAfter("## live").let { live -> assertContains(live, "WS-9 ("); assertContains(live, "WS-10 (") }
    }

    @Test
    fun `the cut pool says how many plannable tasks it did not show and grows with the slots`() {
        val few = pool().plan("slots" to JsonPrimitive(1))
        assertContains(few, "9 more plannable tasks not shown (pool of 15)")
        val many = pool().plan("slots" to JsonPrimitive(8))
        assertFalse("not shown" in many, many)
        assertContains(many, "dispatch plan: 8 windows of 8 slots")
    }

    @Test
    fun `a request from a workspace stays in the workspace's project unless it names one`() {
        val scoped = pool()
        assertFalse("XY-" in scoped.plan("slots" to JsonPrimitive(8)))
        assertContains(scoped.plan("query" to JsonPrimitive("project: XY"), "slots" to JsonPrimitive(1)), "W1 single XY-")
        assertFalse("XY-" in scoped.plan("query" to JsonPrimitive("state: {To do}"), "slots" to JsonPrimitive(8), "since" to JsonPrimitive("none")))
        // Without a workspace the pool spans every mirrored project, as before.
        assertContains(pool(scoped = false).plan("slots" to JsonPrimitive(1), root = repo), "XY-")
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
