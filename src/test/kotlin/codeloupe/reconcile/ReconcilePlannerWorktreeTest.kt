package codeloupe.reconcile

import codeloupe.config.ProtectRule
import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.processes.ProcessEntry
import codeloupe.processes.ProcessKind
import codeloupe.workspace.MergeState
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.TrackerState
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconcilePlannerWorktreeTest {
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private val old = "2026-10-13T09:00:00.000Z"
    private val fresh = "2026-10-13T11:40:00.000Z"
    private val released = HashMap<String, Instant>()

    private fun worktree(
        name: String, state: WorkspaceState = WorkspaceState.LANDED, task: String? = name, resolved: Boolean = true, merged: Boolean = true,
        activity: String = old, path: String = "C:/wt/$name",
    ) = Workspace(
        path, name, "worktree", state, branch = name, head = "a".repeat(40), taskId = task,
        merge = MergeState("origin/main", if (merged) 0 else 2, merged, "subject"),
        tracker = task?.let { TrackerState(if (resolved) "Done" else "In Progress", resolved, "summary") }, lastActivity = activity,
    )

    private fun registry(vararg w: Workspace) =
        WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "C:/ws/Terrio/.git", "origin/main", emptyList(), emptyMap(), w.toList())))

    private fun planner(vararg protect: ProtectRule) =
        ReconcilePlanner(ReconcileConfig(graceMinutes = 60, protect = protect.toList()), { _, w -> released[w] }) { now }

    private fun plan(vararg w: Workspace, processes: List<ProcessEntry> = emptyList(), resources: List<ResourceEntry> = emptyList()) =
        planner().plan(resources, registry(*w), processes).filter { it.kind == TargetKind.WORKTREE }.associateBy { it.workspace!! }

    private fun process(workspace: String, kind: ProcessKind) =
        ProcessEntry(7, 1, kind, "java", "java", "C:/wt/$workspace", 100, "Terrio", workspace, WorkspaceState.LANDED, "C:/wt/$workspace", "cwd")

    @Test
    fun `a landed idle worktree of a resolved task goes by itself and carries what the executor checks again`() {
        val entry = plan(worktree("TER-1")).getValue("TER-1")
        assertEquals(Verdict.AUTO, entry.verdict)
        assertEquals("worktree:C:/wt/TER-1", entry.key)
        assertEquals("C:/ws/Terrio/.git", entry.gitDir)
        assertEquals("TER-1", entry.branch)
        assertEquals("a".repeat(40), entry.head)
        assertFalse(entry.released)
    }

    @Test
    fun `a worktree without a task goes once it landed and is idle`() {
        assertEquals(Verdict.AUTO, plan(worktree("spike", task = null)).getValue("spike").verdict)
    }

    @Test
    fun `an open task, fresh activity, a process or a running container keep a landed worktree`() {
        val running = ResourceEntry(ResourceKind.CONTAINER, "c1", listOf("app"), OwnershipClass.OWNED, "Terrio", "TER-5", null, "labels", WorkspaceState.LANDED, "running", old)
        val p = plan(
            worktree("TER-2", resolved = false), worktree("TER-3", activity = fresh), worktree("TER-4"), worktree("TER-5"),
            processes = listOf(process("TER-4", ProcessKind.OTHER)), resources = listOf(running),
        )
        assertEquals(Verdict.CONFIRM, p.getValue("TER-2").verdict)
        assertEquals(Verdict.KEEP, p.getValue("TER-3").verdict)
        assertEquals(Verdict.KEEP, p.getValue("TER-4").verdict)
        assertEquals(Verdict.KEEP, p.getValue("TER-5").verdict)
    }

    @Test
    fun `active and abandoned worktrees, the main checkout and Claude Code's own worktrees are not planned`() {
        val main = Workspace("C:/ws/Terrio", "Terrio", "main", WorkspaceState.ACTIVE)
        val p = plan(
            main, worktree("TER-6", state = WorkspaceState.ACTIVE, merged = false), worktree("TER-7", state = WorkspaceState.ABANDONED, merged = false),
            worktree("agent-1", task = null, path = "C:/ws/Terrio/.claude/worktrees/agent-1"),
        )
        assertEquals(emptySet(), p.keys)
    }

    @Test
    fun `a released worktree goes when its branch is merged, whatever its task says, and stays unplanned when it is not`() {
        released["TER-8"] = now.minusSeconds(60)
        released["TER-9"] = now.minusSeconds(60)
        val p = plan(
            worktree("TER-8", state = WorkspaceState.ACTIVE, resolved = false, activity = fresh),
            worktree("TER-9", state = WorkspaceState.ACTIVE, merged = false),
        )
        assertEquals(Verdict.AUTO, p.getValue("TER-8").verdict)
        assertTrue(p.getValue("TER-8").released)
        assertNull(p["TER-9"])
    }

    @Test
    fun `a released worktree waits for anything but its build tools, which the same run stops`() {
        released["TER-10"] = now.minusSeconds(60)
        released["TER-11"] = now.minusSeconds(60)
        val p = plan(
            worktree("TER-10"), worktree("TER-11"),
            processes = listOf(process("TER-10", ProcessKind.GRADLE_DAEMON), process("TER-11", ProcessKind.OTHER)),
        )
        assertEquals(Verdict.AUTO, p.getValue("TER-10").verdict)
        assertEquals(Verdict.KEEP, p.getValue("TER-11").verdict)
    }

    @Test
    fun `a repository that names its worktree roots gets only the worktrees under them removed`() {
        val repo = RepoWorkspaces(
            "C:/ws/Terrio", "Terrio", "C:/ws/Terrio/.git", "origin/main", listOf("C:/wt"), emptyMap(),
            listOf(worktree("TER-15"), worktree("old-tool", task = null, path = "C:/hub/repos/old-tool"), worktree("TER-16", path = "C:/wtx/TER-16")),
        )
        val plan = planner().plan(emptyList(), WorkspaceList("now", listOf(repo))).map { it.workspace }
        assertEquals(listOf("TER-15"), plan)
    }

    @Test
    fun `a protect rule keeps a worktree`() {
        val p = ReconcilePlanner(ReconcileConfig(graceMinutes = 60, protect = listOf(ProtectRule(Regex("^TER-12$")))), { _, _ -> null }) { now }
            .plan(emptyList(), registry(worktree("TER-12")))
        assertEquals(Verdict.PROTECTED, p.single().verdict)
    }

    @Test
    fun `an orphan directory of a released workspace goes by itself, others wait for confirmation`() {
        released["TER-13"] = now.minusSeconds(60)
        val dirs = listOf("TER-13", "TER-14").map { Workspace("C:/wt/$it", it, "directory", WorkspaceState.ORPHAN) }
        val p = planner().plan(emptyList(), registry(*dirs.toTypedArray())).associateBy { it.workspace!! }
        assertEquals(Verdict.AUTO, p.getValue("TER-13").verdict)
        assertTrue(p.getValue("TER-13").released)
        assertEquals(Verdict.CONFIRM, p.getValue("TER-14").verdict)
    }

    @Test
    fun `the worktree switch is on unless the config says false`() {
        fun parse(text: String) = ReconcileConfig.parse(Json.parseToJsonElement(text).jsonObject)
        assertTrue(ReconcileConfig().worktrees)
        assertTrue(parse("""{"auto": false}""").worktrees)
        assertFalse(parse("""{"worktrees": false}""").worktrees)
    }
}
