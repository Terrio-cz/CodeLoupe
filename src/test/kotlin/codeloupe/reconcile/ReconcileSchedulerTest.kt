package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.docker.ResourceReport
import codeloupe.events.Event
import codeloupe.events.EventTypes
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconcileSchedulerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val triggers = CopyOnWriteArrayList<String>()

    private suspend fun waitFor(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < until) delay(20)
    }

    @Test
    fun `with auto on the first run follows the start, so a restart finishes the interrupted cleanup`() = runBlocking {
        val config = ReconcileConfig(auto = true, graceMinutes = 0)
        val reconciler = knownRepo(config)
        ReconcileScheduler(scope, config, reconciler, MutableSharedFlow(), { null }, {}, startDelayMs = 0, tickMs = 50, jobDebounceMs = 0).start()
        waitFor { triggers.isNotEmpty() }
        assertEquals(listOf("start"), triggers.toList())
        scope.cancel()
    }

    @Test
    fun `with auto off the scheduler never runs`() = runBlocking {
        val config = ReconcileConfig(auto = false, graceMinutes = 0)
        val reconciler = knownRepo(config)
        val events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
        ReconcileScheduler(scope, config, reconciler, events, { Instant.now() }, {}, startDelayMs = 0, tickMs = 20, jobDebounceMs = 0).start()
        events.tryEmit(Event(1, "now", EventTypes.JOB_FINISHED, JsonObject(emptyMap())))
        delay(300)
        assertEquals(emptyList(), triggers.toList())
        scope.cancel()
    }

    @Test
    fun `a finished job triggers a run, other events do not`() = runBlocking {
        val config = ReconcileConfig(auto = true, graceMinutes = 0)
        val events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 4)
        ReconcileScheduler(scope, config, knownRepo(config), events, { null }, {}, startDelayMs = 60_000, tickMs = 60_000, jobDebounceMs = 10).start()
        delay(200)
        events.emit(Event(1, "now", EventTypes.OVERLAY_REFRESHED, JsonObject(emptyMap())))
        delay(200)
        assertEquals(emptyList(), triggers.toList())
        events.emit(Event(2, "now", EventTypes.JOB_FINISHED, JsonObject(emptyMap())))
        waitFor { triggers.isNotEmpty() }
        assertEquals(listOf("job"), triggers.toList())
        scope.cancel()
    }

    // A reconciler with one landed, labelled volume in Docker; the recorder notes the trigger of each attempt.
    private fun knownRepo(config: ReconcileConfig): Reconciler {
        val volume = ResourceEntry(ResourceKind.VOLUME, "v", listOf("data"), OwnershipClass.OWNED, "Terrio", "TER-1", null, "labels", WorkspaceState.LANDED, null, "2026-10-01T00:00:00.000Z")
        var present = true
        val list = WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "c", "main", emptyList(), emptyMap(), emptyList())))
        return Reconciler(
            config, { list }, { ResourceReport("now", "fake", resources = if (present) listOf(volume) else emptyList()) }, ReconcilePlanner(config),
            { entry -> present = false; ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.REMOVED) },
            ReconcileState(TestRepos.tmpDir("sched").resolve("state.json"), config),
        ) { _, trigger -> triggers += trigger }
    }
}
