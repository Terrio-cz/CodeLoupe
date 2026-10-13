package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ReconcileConfig
import codeloupe.docker.ResourceReport
import codeloupe.workspace.MergeState
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.TrackerState
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcilerWorktreeTest {
    private val clock = Instant.parse("2026-10-13T12:00:00Z")
    private val removed = ArrayList<String>()

    private val landed = Workspace(
        "C:/wt/CL-1", "CL-1", "worktree", WorkspaceState.LANDED, branch = "CL-1", head = "a".repeat(40), taskId = "CL-1",
        merge = MergeState("origin/main", 0, true, "s"), tracker = TrackerState("Done", true, "s"), lastActivity = "2026-10-12T00:00:00.000Z",
    )

    private fun reconciler(config: ReconcileConfig) = Reconciler(
        config,
        { WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/CodeLoupe", "CodeLoupe", "C:/ws/CodeLoupe/.git", "origin/main", emptyList(), emptyMap(), listOf(landed)))) },
        { ResourceReport("now", "fake") },
        ReconcilePlanner(config) { clock },
        { entry -> removed += entry.key; ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.REMOVED) },
        ReconcileState(TestRepos.tmpDir("reconcile-wt").resolve("state.json"), config) { clock },
    ) { _, _ -> }

    @Test
    fun `finished worktrees go unattended with Docker auto off, unless the worktree switch is off`() = runBlocking {
        reconciler(ReconcileConfig(auto = false)).run("interval", auto = false)
        assertEquals(listOf("worktree:C:/wt/CL-1"), removed)
        removed.clear()
        reconciler(ReconcileConfig(auto = false, worktrees = false)).run("interval", auto = false)
        assertEquals(emptyList(), removed)
    }
}
