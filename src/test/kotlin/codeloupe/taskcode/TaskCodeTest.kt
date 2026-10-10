package codeloupe.taskcode

import codeloupe.TestRepos
import codeloupe.TestRepos.git
import codeloupe.config.Config
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.doc.DocMemory
import codeloupe.tools.TaskCodeTool
import codeloupe.tools.TaskContextTool
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `task_code` on a fixture history: a task landed by a merge, one landed as a plain commit, a branch-side merge of main
 * that must not count, an open task predicted from its text and held by a worktree, incremental and rewritten scans.
 */
class TaskCodeTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample", mapOf(BILLING to billing(), USE to USE_TEXT, ROUTES to ROUTES_TEXT))
    private val config = Config(TestRepos.tmpDir("home"), 0, 60_000, buildTimeoutMs = 120_000, buildHeapMb = 512, defaultRoot = null, overlayCheckMs = 0)
    private val scope = CoroutineScope(Dispatchers.Default)
    private val registry = Registry(config, JobQueue(scope))
    private val fake = RecordedYouTrack()
    private val mirrorStore = MirrorStore(TestRepos.tmpDir("tasks").resolve("t.db"))
    private val instance = TrackerInstance("t", "youtrack", "https://t.example", listOf("CL"), TokenSource.Env("UNUSED") { emptyMap() })
    private val trackers: Trackers
    private var clock = Instant.now().epochSecond
    private val merge: String
    private val plain: String

    init {
        fake.edit("CL-91", 1_791_500_000_000L) { issue ->
            issue["description"] = JsonPrimitive(
                "## Context\nThe `Billing.total` path is read by `src/main/kotlin/demo/Use.kt` and served on `GET /v1/orders/{id}`.\n" +
                    "Billing rules move to a new `src/main/kotlin/demo/Later.kt`; `Nonexistent.foo` is gone. The page `src/views/Admin.jsx:320` and `notes.md/audit.md` are not ours.\n\n" +
                    "## Acceptance criteria\n- [ ] Routes keep answering\n- [ ] `useAll()` stays green\n- [ ] `docs/notes.md` says so\n",
            )
        }
        val mirror = TrackerMirror(instance, YouTrackAdapter(fake), mirrorStore, 30_000)
        runBlocking { mirror.syncProject("CL", 0) }
        trackers = Trackers(listOf(mirror), TrackerSettings(listOf(instance)), scope)

        // CL-16: one plain commit on main.
        write(repo, BILLING, billing(total = "return a + 1"))
        commit(repo, "CL-16 bump totals")
        plain = git(repo, "rev-parse", "HEAD")
        // CL-17: a branch that adds a member and a file, merges main (which moved on with CL-56 docs) and lands by merge.
        git(repo, "checkout", "-q", "-b", "CL-17")
        write(repo, BILLING, billing(total = "return a + 1", extra = "\n    fun discount(): Int = 5\n"))
        write(repo, FRESH, "package demo\n\nclass Fresh {\n    fun hello() = Billing().keep()\n}\n")
        commit(repo, "CL-17 add discount")
        git(repo, "checkout", "-q", "main")
        write(repo, "docs/notes.md", "notes\n")
        commit(repo, "CL-56 document UTF-8 handling")
        git(repo, "checkout", "-q", "CL-17")
        git(repo, "merge", "-q", "--no-edit", "-m", "CL-17 merge main", "main", env = nextTime())
        git(repo, "checkout", "-q", "main")
        git(repo, "merge", "-q", "--no-ff", "-m", "Merge CL-17 add discount", "CL-17", env = nextTime())
        merge = git(repo, "rev-parse", "HEAD")
    }

    @Test
    fun `a task landed by a merge lists the branch's files and declarations, not what it merged in`() {
        val text = answer("CL-17")
        assertTrue(text.startsWith("CL-17 Done · Feature · Critical ‹CL-2› changes(root): semantic diff for review"), text)
        assertContains(text, "landed ${merge.take(7)} ")
        assertContains(text, "(merge of 1 commit) · 2 files, 2 declarations (+2)")
        assertContains(text, "$BILLING\n  + 12-12  [Billing] fun discount(): Int")
        assertContains(text, "$FRESH  (new)\n  + 3-5  class Fresh")
        assertFalse("notes.md" in text, "main's docs commit came in through the branch-side merge: $text")
        assertFalse("total" in text, "the member CL-16 changed is not CL-17's: $text")
    }

    @Test
    fun `a plain commit is its own landing and a body change is marked`() {
        val text = answer("CL-16")
        assertContains(text, "landed ${plain.take(7)} ")
        assertContains(text, "(1 commit) · 1 files, 1 declarations (~1)")
        assertContains(text, "  ~ 4-6  [Billing] fun total(a: Int): Int")
    }

    @Test
    fun `a declaration lists the tasks that changed it and the ones that only touched its file`() {
        val text = answer("Billing.total")
        assertTrue(text.startsWith("tasks that touched Billing.total ($BILLING:4-6):"), text)
        assertContains(text, "CL-16 landed ${plain.take(7)} ")
        assertContains(text, "  ~  Worktree overlays and lazy base sync (Done)")
        assertContains(text, "file touched, this declaration not: CL-17 (${merge.take(7)})")
        assertFalse("CL-56" in text, text)
    }

    @Test
    fun `a path lists every task that touched it with the marks of its changes`() {
        val text = answer(BILLING)
        assertContains(text, "CL-17 landed ${merge.take(7)}")
        assertContains(text, "CL-16 landed ${plain.take(7)}")
        assertTrue(text.indexOf("CL-17") < text.indexOf("CL-16"), "newest landing first: $text")
        assertContains(text, "  +  changes(root)")
        assertContains(text, "  ~  Worktree overlays")
    }

    @Test
    fun `an open task is predicted from its text with marks and evidence, and its worktree is shown`() {
        val worktree = TestRepos.tmpDir("wt").resolve("cl91")
        git(repo, "worktree", "add", "-q", "-b", "CL-91", worktree.toString())
        write(worktree, USE, USE_TEXT.replace("total(1)", "total(2)"))
        val text = answer("CL-91")
        assertTrue(text.startsWith("CL-91 To do · Feature · Major ‹CL-4› Task ↔ code links"), text)
        assertContains(text, "in progress: ${worktree.toRealPath().toString().replace('\\', '/')} (branch CL-91), 1 files changed against the merge-base: $USE")
        assertContains(text, "predicted from the issue text (= sure · ~ likely · ? guess · + new):")
        assertContains(text, Regex("= $BILLING:4-6  \\[Billing] fun total\\(a: Int\\): Int +← `Billing.total` in Context"))
        assertContains(text, Regex("= $USE +← `src/main/kotlin/demo/Use.kt` in Context"))
        assertContains(text, Regex("= $USE:3-3  fun useAll\\(\\): Int +← `useAll\\(\\)` in criterion 2"))
        assertContains(text, Regex("~ $ROUTES:4  holds `/v1/orders/` +← `GET /v1/orders/\\{id}` in Context"))
        assertContains(text, Regex("\\+ src/main/kotlin/demo/Later.kt  not in the repository: new\\? +← `src/main/kotlin/demo/Later.kt` in Context"))
        assertContains(text, "not in the index: `Nonexistent.foo`")
        assertFalse(text.lines().any { it.startsWith("+ src/views") || it.startsWith("+ notes.md") }, "a path whose folder is missing is not a file to come: $text")
        assertContains(text, Regex("= docs/notes.md  \\(not indexed\\) +← `docs/notes.md` in criterion 3"))
        assertFalse("landed" in text, text)

        val onUse = answer(USE)
        assertContains(onUse, "open tasks predicted to touch it:\nCL-91 = Task ↔ code links from git history and descriptions (To do)  ← `src/main/kotlin/demo/Use.kt` in Context")
    }

    @Test
    fun `a later commit is found by an incremental scan and a rewritten history by a fresh one`() {
        answer("CL-16")
        write(repo, USE, USE_TEXT.replace("total(1)", "total(3)"))
        commit(repo, "CL-26 use more")
        val first = git(repo, "rev-parse", "HEAD")
        assertContains(answer("CL-26"), "landed ${first.take(7)} ")
        git(repo, "commit", "-q", "--amend", "-m", "CL-27 use more, renamed", env = nextTime())
        val amended = git(repo, "rev-parse", "HEAD")
        assertContains(answer("CL-27"), "landed ${amended.take(7)} ")
        assertFalse("landed" in answer("CL-26"), "the amended-away commit is forgotten")
        assertContains(answer("ABC-1"), "no declaration or file matches \"ABC-1\" (a task id looks like CL-<n>)", message = "the mirrored projects decide what a task id is")
        assertContains(answer("CL-99"), "no commits of CL-99 on the default branch")
    }

    @Test
    fun `without a tracker any id-like word is a task, apart from technical names`() {
        val alone = Trackers(emptyList(), TrackerSettings(emptyList()), scope)
        val text = answer("CL-56", TaskCodeTool(alone))
        assertTrue(text.startsWith("CL-56\nlanded "), text)
        assertContains(text, "other files: docs/notes.md")
        assertContains(answer("UTF-8", TaskCodeTool(alone)), "no declaration or file matches \"UTF-8\"")
    }

    @Test
    fun `task_context gives the planner's pack in one call and a repeated call costs one line`() {
        val text = context("CL-91")
        assertContains(text, "## issue\nCL-91 Task ↔ code links from git history and descriptions")
        assertContains(text, "Criteria 0/3:")
        assertContains(text, "## touch")
        assertContains(text, Regex("= $BILLING:4-6 .*`Billing.total` in Context"))
        assertContains(text, "## declarations\n$BILLING\n  3-13  class Billing\n  4-6  [Billing] fun total(a: Int): Int")
        assertContains(text, "## prior\nCL-17 landed ${merge.take(7)} ")
        assertContains(text, "CL-16 landed ${plain.take(7)} ")
        assertTrue(text.indexOf("CL-17 landed") < text.indexOf("CL-16 landed"), "newest landing first: $text")
        assertContains(text, "‹Billing.kt")
        assertFalse("CL-91 landed" in text, "the task itself is not its own prior task")

        val again = context("CL-91")
        assertTrue(again.startsWith("context:CL-91 unchanged since your read at "), again)
        assertTrue(again.length < 300, "${again.length} chars")
        assertContains(context("CL-91", session = repo.resolve("src").toString()), "## declarations", message = "another caller gets the pack")
        assertContains(context("CL-91", "since" to "none"), "## declarations")
    }

    @Test
    fun `a changed task gets only the sections that changed, and sections can be picked`() {
        val worktree = TestRepos.tmpDir("wt").resolve("cl91c")
        git(repo, "worktree", "add", "-q", "-b", "CL-91", worktree.toString())
        context("CL-91")
        write(worktree, USE, USE_TEXT.replace("total(1)", "total(5)"))
        val delta = context("CL-91")
        assertContains(delta, "changed sections in full, unchanged omitted (issue, ")
        assertContains(delta, "in progress: ")
        assertFalse("## prior" in delta, delta)
        assertTrue(context("CL-91").startsWith("context:CL-91 unchanged"))

        val picked = context("CL-91", "sections" to "prior", "since" to "none")
        assertTrue(picked.startsWith("## prior\nCL-17 landed"), picked)
        assertFalse("## issue" in picked)
        assertTrue(context("CL-91", "sections" to "prior").startsWith("section prior unchanged since your read"))
        val digest = context("CL-91", "view" to "digest", "since" to "none")
        assertTrue(digest.length <= 1000, "${digest.length} chars")
        assertContains(digest, "sections: issue(")
    }

    @Test
    fun `a landed task's pack lists its own files' declarations and the tasks before it`() {
        val text = context("CL-17")
        assertContains(text, "landed ${merge.take(7)} ")
        assertContains(text, "## declarations\n$BILLING")
        assertContains(text, "fun discount(): Int")
        assertContains(text, "CL-16 landed ${plain.take(7)} ")
        assertFalse("CL-17 landed" in text)
        assertTrue(runCatching { context("ABC-1") }.exceptionOrNull()?.message.orEmpty().startsWith("no tracker mirrors the project of 'ABC-1'"))
    }

    @Test
    fun `the pack carries the description, the comments, who references the touched code and the rules that apply`() {
        write(repo, "AGENTS.md", "# Demo\n\n## Modules\n- The `Billing` rules live in the billing module and nowhere else.\n- Unrelated: releases are tagged by hand.\n")
        val text = context("CL-91")
        val description = text.substringAfter("## description\n").substringBefore("\n## ")
        assertTrue(description.startsWith("### Context\nThe `Billing.total` path is read by"), description)
        assertFalse("Routes keep answering" in description, "the checklist is shown by the issue section only")
        assertContains(text, Regex("## callers\n$BILLING {2}← .*Use\\.kt"))
        assertContains(text, "## norms\nAGENTS.md 5 lines, sections (first line): Demo 1 · Modules 3")
        assertContains(text, "L4 [Modules] - The `Billing` rules live in the billing module")
        assertFalse("releases are tagged" in text, text)
        assertContains(context("CL-91", "sections" to "callers", "since" to "none"), "Use.kt")
    }

    private val contextTool = TaskContextTool(trackers, DocMemory())

    private fun context(id: String, vararg more: Pair<String, String>, session: String = repo.toString()): String = runBlocking {
        contextTool.answer(registry, session, ToolArgs(buildJsonObject {
            put("id", JsonPrimitive(id))
            more.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }))
    }

    private fun answer(query: String, tool: TaskCodeTool = TaskCodeTool(trackers)): String = runBlocking {
        tool.answer(registry, repo.toString(), ToolArgs(buildJsonObject { put("query", JsonPrimitive(query)) }))
    }

    private fun write(root: Path, path: String, text: String) {
        root.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun commit(root: Path, message: String) {
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message, env = nextTime())
    }

    /** Every commit gets its own second: with equal times the newest-first order of a landing list is a coin toss. */
    private fun nextTime(): Map<String, String> = "${++clock} +0000".let { mapOf("GIT_AUTHOR_DATE" to it, "GIT_COMMITTER_DATE" to it) }

    private companion object {
        const val BILLING = "src/main/kotlin/demo/Billing.kt"
        const val USE = "src/main/kotlin/demo/Use.kt"
        const val FRESH = "src/main/kotlin/demo/Fresh.kt"
        const val ROUTES = "src/main/kotlin/demo/Routes.kt"
        const val USE_TEXT = "package demo\n\nfun useAll(): Int = Billing().total(1) + Billing().tax(2)\n"
        const val ROUTES_TEXT = "package demo\n\nobject Routes {\n    const val ORDER = \"/v1/orders/{id}\"\n}\n"

        fun billing(total: String = "return a", extra: String = "") =
            "package demo\n\nclass Billing {\n    fun total(a: Int): Int {\n        $total\n    }\n\n    fun tax(a: Int): Int = a / 10\n\n    fun keep(): Int = 1\n$extra}\n"
    }
}
