package codeloupe.reconcile

import codeloupe.docker.FakeDockerEngine
import codeloupe.docker.OwnershipClass
import codeloupe.workspace.WorkspaceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcileExecutorContainerTest {
    private fun inspect(status: String, workspace: String = "TER-9") =
        """{"Id":"abc123abc123","Name":"/db","State":{"Status":"$status"},"Config":{"Labels":{"codeloupe.repo":"Terrio","codeloupe.workspace":"$workspace"}}}"""

    private fun engine(body: String?) = FakeDockerEngine { method, path ->
        when {
            method == "GET" && path.startsWith("/containers/abc123/json") -> body?.let { 200 to it } ?: (404 to """{"message":"no such container"}""")
            method == "POST" && path.startsWith("/containers/abc123/stop") -> 204 to ""
            method == "DELETE" && path.startsWith("/containers/abc123") -> 204 to ""
            else -> 500 to "{}"
        }
    }

    private fun entry(running: Boolean = false, ownership: OwnershipClass = OwnershipClass.OWNED) =
        PlanEntry("container:abc123", TargetKind.CONTAINER, "db", "Terrio", "TER-9", ownership, WorkspaceState.LANDED, Verdict.AUTO, "the workspace landed", running = running)

    private fun run(engine: FakeDockerEngine, entry: PlanEntry) = ReconcileExecutor({ engine.api }).execute(entry)

    @Test
    fun `a stopped container is stopped and removed`() {
        engine(inspect("exited")).use { engine ->
            assertEquals(ActionOutcome.REMOVED, run(engine, entry()).outcome)
            assertTrue(engine.requests.any { it.startsWith("DELETE /containers/abc123") })
        }
    }

    @Test
    fun `a container started after the plan saw it stopped is left alone`() {
        engine(inspect("running")).use { engine ->
            val result = run(engine, entry(running = false))
            assertEquals(ActionOutcome.BLOCKED, result.outcome)
            assertTrue(engine.requests.none { it.startsWith("POST") || it.startsWith("DELETE") })
        }
    }

    @Test
    fun `a container the plan saw running is stopped`() {
        engine(inspect("running")).use { engine ->
            assertEquals(ActionOutcome.REMOVED, run(engine, entry(running = true)).outcome)
            assertTrue(engine.requests.any { it.startsWith("POST /containers/abc123/stop") })
        }
    }

    @Test
    fun `a container whose labels no longer name the workspace is left alone`() {
        engine(inspect("exited", workspace = "TER-10")).use { engine ->
            assertEquals(ActionOutcome.BLOCKED, run(engine, entry()).outcome)
            assertTrue(engine.requests.none { it.startsWith("POST") || it.startsWith("DELETE") })
        }
    }

    @Test
    fun `an adopted container is not compared with labels it never had`() {
        engine("""{"Id":"abc123abc123","Name":"/db","State":{"Status":"exited"},"Config":{"Labels":{}}}""").use { engine ->
            assertEquals(ActionOutcome.REMOVED, run(engine, entry(ownership = OwnershipClass.ADOPTED)).outcome)
        }
    }

    @Test
    fun `a container that is gone is reported as gone`() {
        engine(null).use { engine ->
            assertEquals(ActionOutcome.GONE, run(engine, entry()).outcome)
        }
    }
}
