package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ReconcileConfig
import codeloupe.docker.ResourceReport
import codeloupe.platform.IsoTime
import codeloupe.processes.ProcessInventory
import codeloupe.processes.ProcessReport
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceRef
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerIncompleteReadTest {
    private val clock = Instant.parse("2026-10-08T12:00:00Z")
    private val config = ReconcileConfig(graceMinutes = 0)
    private val home = TestRepos.tmpDir("incomplete")
    private val store = ReleaseStore(home.resolve("releases.json")) { clock }

    private fun reconciler(processes: ProcessReport): Reconciler = Reconciler(
        config, { WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "c", "main", emptyList(), emptyMap(), emptyList()))) },
        { ResourceReport("now", "fake") }, ReconcilePlanner(config, store::releasedAt) { clock },
        { error("nothing to remove") }, ReconcileState(home.resolve("state.json"), config) { clock }, store,
        processes = { processes },
    ) { _, _ -> }

    @Test
    fun `a process table that could not be read does not complete a release, whose build tools may still run`() = runBlocking {
        store.mark(WorkspaceRef("Terrio", "TER-1"))
        reconciler(ProcessReport(IsoTime.now(), problems = listOf("${ProcessInventory.PROBLEM} access denied"))).run("retry", auto = false)
        assertEquals(1, store.all().size, "the process table was not read: nothing proves that the workspace is clean")

        reconciler(ProcessReport(IsoTime.now())).run("retry", auto = false)
        assertTrue(store.isEmpty())
    }

    @Test
    fun `a problem with another repository does not hold a release open forever`() = runBlocking {
        store.mark(WorkspaceRef("Terrio", "TER-1"))
        reconciler(ProcessReport(IsoTime.now(), problems = listOf("C:/gone/repo: no such repository"))).run("retry", auto = false)
        assertTrue(store.isEmpty())
    }
}
