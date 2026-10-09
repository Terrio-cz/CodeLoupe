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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A protect rule that cannot be read must not silently stop protecting. */
class ReconcileProtectConfigTest {
    private fun parse(json: String) = ReconcileConfig.parse(Json.parseToJsonElement(json).jsonObject["reconcile"]?.jsonObject)

    @Test
    fun `rules that cannot be read are counted, the readable ones are kept`() {
        val config = parse("""{"reconcile":{"protect":[{"match":"^keep-"},{"match":"^broken("},{"kinds":["volume"]},"text"]}}""")
        assertEquals(1, config.protect.size)
        assertEquals(3, config.invalidProtect)
        assertEquals(0, parse("""{"reconcile":{"protect":[{"match":"^keep-"}]}}""").invalidProtect)
    }

    @Test
    fun `with a protect rule that cannot be read nothing is removed and the plan says why`() = runBlocking {
        val config = parse("""{"reconcile":{"graceMinutes":0,"protect":[{"match":"^shared-stack(("}]}}""")
        val docker = mutableListOf(ResourceEntry(ResourceKind.VOLUME, "id-data", listOf("data-1"), OwnershipClass.OWNED, "Terrio", "TER-1", null, "labels", WorkspaceState.LANDED, null, "2026-10-01T00:00:00.000Z"))
        val removed = ArrayList<String>()
        val home = TestRepos.tmpDir("protect")
        val reconciler = Reconciler(
            config, { WorkspaceList("now", listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "c", "main", emptyList(), emptyMap(), emptyList()))) },
            { ResourceReport("now", "fake", resources = docker) }, ReconcilePlanner(config) { Instant.parse("2026-10-08T12:00:00Z") },
            { entry -> removed += entry.key; ActionResult(entry.key, entry.kind, entry.name, entry.workspace, ActionOutcome.REMOVED) },
            ReconcileState(home.resolve("state.json"), config),
        ) { _, _ -> }

        val run = reconciler.run("start", auto = true, confirm = setOf("volume:data-1"))

        assertEquals(emptyList(), removed)
        assertEquals(emptyList(), run.actions)
        assertTrue(run.remaining.problems.any { it.contains("protect") }, run.remaining.problems.toString())
        assertTrue(reconciler.plan().problems.any { it.contains("protect") })
    }
}
