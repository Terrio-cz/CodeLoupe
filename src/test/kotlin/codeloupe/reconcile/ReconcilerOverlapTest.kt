package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ReconcileConfig
import codeloupe.docker.ResourceReport
import codeloupe.processes.ProcessReport
import codeloupe.platform.IsoTime
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.WorkspaceList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilerOverlapTest {
    private val config = ReconcileConfig(graceMinutes = 0)
    private val list = WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "c", "main", emptyList(), emptyMap(), emptyList())))
    private val events = java.util.Collections.synchronizedList(ArrayList<String>())

    private fun reconciler() = Reconciler(
        config,
        registry = { events += "registry"; list },
        inventory = { events += "docker"; it.await(); events += "docker joined"; ResourceReport(IsoTime.now(), "fake") },
        planner = ReconcilePlanner(config),
        executor = { error("must not run") },
        state = ReconcileState(TestRepos.tmpDir("overlap").resolve("state.json"), config),
        processes = { events += "processes"; it.await(); ProcessReport(IsoTime.now()) },
        planRegistry = { events += "plan registry"; list },
        record = { _, _ -> },
    )

    // The registry read finishes only once Docker has started: it cannot, unless the two overlap.
    @Test
    fun `the dry run reads Docker and the process table while the registry scan runs`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reconciler = Reconciler(
            config,
            registry = { list },
            inventory = { started.complete(Unit); it.await(); ResourceReport(IsoTime.now(), "fake") },
            planner = ReconcilePlanner(config),
            executor = { error("must not run") },
            state = ReconcileState(TestRepos.tmpDir("overlap").resolve("state.json"), config),
            planRegistry = { withTimeout(5_000) { started.await() }; list },
            record = { _, _ -> },
        )
        assertEquals(0, reconciler.plan().entries.size)
    }

    @Test
    fun `a run reads the registry first, then Docker and the process table`() = runBlocking {
        val reconciler = reconciler()
        reconciler.run("manual", auto = true)
        assertEquals(listOf("registry", "docker", "docker joined", "processes"), events)
    }

    @Test
    fun `the dry run joins both reads to the same registry list`() = runBlocking {
        val reconciler = reconciler()
        reconciler.plan()
        assertTrue(events.containsAll(listOf("plan registry", "docker", "docker joined", "processes")))
        assertEquals(1, events.count { it == "plan registry" })
    }
}
