package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.docker.ResourceReport
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcilerSharedScanTest {
    private val config = ReconcileConfig(graceMinutes = 0)
    private val fresh = AtomicInteger()
    private val shared = AtomicInteger()
    private val changed = AtomicInteger()

    private fun list(label: String) = WorkspaceList(label, listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "c", "main", emptyList(), emptyMap(), emptyList())))

    private val volume = ResourceEntry(ResourceKind.VOLUME, "v", listOf("data"), OwnershipClass.OWNED, "Terrio", "TER-1", null, "labels", WorkspaceState.LANDED, null, "2026-10-01T00:00:00.000Z")
    private var present = false

    private fun reconciler() = Reconciler(
        config, { fresh.incrementAndGet(); list("fresh") }, { ResourceReport("now", "fake", resources = if (present) listOf(volume) else emptyList()) }, ReconcilePlanner(config),
        { entry -> present = false; ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.REMOVED) },
        ReconcileState(TestRepos.tmpDir("shared-scan").resolve("state.json"), config),
        planRegistry = { shared.incrementAndGet(); list("shared") }, changed = { changed.incrementAndGet() },
    ) { _, _ -> }

    @Test
    fun `the dry run may read the shared scan but a run and the scheduler always read the registry afresh`() = runBlocking {
        val reconciler = reconciler()
        reconciler.plan()
        assertEquals(1 to 0, shared.get() to fresh.get())
        reconciler.run("manual", auto = true)
        reconciler.run("interval", auto = true)
        assertEquals(1, shared.get(), "a run never takes the shared scan")
        assertEquals(2, fresh.get())
    }

    @Test
    fun `a run that removed something drops the shared scan, one that did nothing leaves it`() = runBlocking {
        val reconciler = reconciler()
        reconciler.run("interval", auto = true)
        assertEquals(0, changed.get())
        present = true
        reconciler.run("interval", auto = true)
        assertEquals(1, changed.get())
    }
}
