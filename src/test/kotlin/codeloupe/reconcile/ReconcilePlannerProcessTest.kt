package codeloupe.reconcile

import codeloupe.config.ProtectRule
import codeloupe.config.ReconcileConfig
import codeloupe.processes.ProcessEntry
import codeloupe.processes.ProcessKind
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilePlannerProcessTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val started = Instant.parse("2026-10-08T09:00:00Z").toEpochMilli()
    private val registry = WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "C:/ws/Terrio/.git", "main", emptyList(), emptyMap(), listOf(Workspace("C:/ws/wt/TER-1", "TER-1", "worktree", WorkspaceState.LANDED)))))

    private fun process(pid: Long, workspace: String, state: WorkspaceState, kind: ProcessKind = ProcessKind.GRADLE_DAEMON, startMs: Long = started, line: String = "java GradleDaemon") =
        ProcessEntry(pid, startMs, kind, "java", line, "C:/ws/wt/$workspace", 300, "Terrio", workspace, state, "C:/ws/wt/$workspace", "cwd")

    private fun planner(releasedAt: Instant? = null, vararg protect: ProtectRule) =
        ReconcilePlanner(ReconcileConfig(protect = protect.toList()), { _, workspace -> releasedAt.takeIf { workspace == "TER-1" } }) { now }

    private fun plan(planner: ReconcilePlanner, vararg processes: ProcessEntry) = planner.plan(emptyList(), registry, processes.toList())

    @Test
    fun `a build tool of a released workspace is stopped without asking, whatever state the workspace is in`() {
        val entry = plan(planner(releasedAt = now.minusSeconds(60)), process(1, "TER-1", WorkspaceState.ACTIVE)).single()
        assertEquals(Verdict.AUTO, entry.verdict)
        assertTrue(entry.released)
        assertEquals(TargetKind.PROCESS, entry.kind)
        assertEquals("process:1:$started", entry.key)
        assertEquals("C:/ws/wt/TER-1", entry.path)
        assertEquals("gradle-daemon pid 1 (300 MB)", entry.name)
    }

    @Test
    fun `without a release, an active workspace keeps its daemons and the others wait for a confirmation`() {
        val plan = plan(
            planner(),
            process(1, "TER-1", WorkspaceState.ACTIVE), process(2, "TER-1", WorkspaceState.LANDED), process(3, "TER-1", WorkspaceState.ABANDONED), process(4, "TER-1", WorkspaceState.ORPHAN),
        ).associate { it.key.split(':')[1].toInt() to it.verdict }
        assertEquals(mapOf(1 to Verdict.KEEP, 2 to Verdict.CONFIRM, 3 to Verdict.CONFIRM, 4 to Verdict.CONFIRM), plan)
    }

    @Test
    fun `only build tools are planned, a daemon that started after the release is not covered by it, and a protected one is never touched`() {
        val released = planner(releasedAt = now.minusSeconds(60))
        val plan = plan(
            released,
            process(1, "TER-1", WorkspaceState.LANDED, ProcessKind.OTHER, line = "node server.js"), process(2, "TER-1", WorkspaceState.LANDED, ProcessKind.GRADLE_CLIENT),
            process(3, "TER-1", WorkspaceState.LANDED, ProcessKind.KOTLIN_DAEMON), process(4, "TER-1", WorkspaceState.LANDED, startMs = now.toEpochMilli()),
        )
        assertEquals(mapOf(3 to Verdict.AUTO, 4 to Verdict.CONFIRM), plan.associate { it.key.split(':')[1].toInt() to it.verdict })

        val protectedPlan = plan(planner(now.minusSeconds(60), ProtectRule(Regex("keep-me"))), process(5, "TER-1", WorkspaceState.LANDED, line = "java -Dx=keep-me GradleDaemon"))
        assertEquals(Verdict.PROTECTED, protectedPlan.single().verdict)
        assertTrue(!protectedPlan.single().released)
    }

    @Test
    fun `processes come first in the order of removal`() {
        val plan = plan(planner(now.minusSeconds(60)), process(1, "TER-1", WorkspaceState.LANDED))
        assertEquals(TargetKind.PROCESS, plan.minBy { it.kind.ordinal }.kind)
        assertEquals(0, TargetKind.PROCESS.ordinal)
    }
}
