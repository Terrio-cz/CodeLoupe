package codeloupe.reconcile

import codeloupe.config.ProtectRule
import codeloupe.config.ReconcileConfig
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceEntry
import codeloupe.docker.ResourceKind
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcilePlannerTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")
    private val old = "2026-10-06T10:00:00.000Z"
    private val fresh = "2026-10-08T11:30:00.000Z"

    private fun registry(vararg extra: Workspace) = WorkspaceList(
        "now",
        listOf(RepoWorkspaces("C:/ws/Terrio", "Terrio", "C:/ws/Terrio/.git", "main", emptyList(), emptyMap(), extra.toList())),
    )

    private fun resource(
        kind: ResourceKind, name: String, workspace: String?, state: WorkspaceState?, ownership: OwnershipClass = OwnershipClass.OWNED,
        created: String = old, containerState: String? = null, repo: String = "Terrio", id: String = "id-$name",
    ) = ResourceEntry(kind, id, listOf(name), ownership, repo, workspace, null, "labels", state, containerState, created)

    private fun planner(vararg protect: ProtectRule) = ReconcilePlanner(ReconcileConfig(graceMinutes = 60, protect = protect.toList())) { now }

    private fun verdicts(vararg resources: ResourceEntry, registry: WorkspaceList = registry()) =
        planner().plan(resources.toList(), registry).associate { it.name to it.verdict }

    @Test
    fun `a labelled resource of a landed workspace is cleaned by itself, in the order containers networks volumes images`() {
        val plan = planner().plan(
            listOf(
                resource(ResourceKind.IMAGE, "terrio-importer-app:ter-1", "TER-1", WorkspaceState.LANDED),
                resource(ResourceKind.VOLUME, "terrio-ter-1_data", "TER-1", WorkspaceState.LANDED),
                resource(ResourceKind.NETWORK, "terrio-ter-1_default", "TER-1", WorkspaceState.LANDED),
                resource(ResourceKind.CONTAINER, "terrio-ter-1-app-1", "TER-1", WorkspaceState.LANDED, containerState = "exited"),
            ),
            registry(),
        )
        assertEquals(setOf(Verdict.AUTO), plan.map { it.verdict }.toSet())
        assertEquals(listOf(TargetKind.CONTAINER, TargetKind.NETWORK, TargetKind.VOLUME, TargetKind.IMAGE), plan.map { it.kind })
        assertEquals(listOf("container:id-terrio-ter-1-app-1", "network:id-terrio-ter-1_default", "volume:terrio-ter-1_data", "image:id-terrio-importer-app:ter-1"), plan.map { it.key })
    }

    @Test
    fun `only landed workspaces release their resources on their own`() {
        val v = verdicts(
            resource(ResourceKind.VOLUME, "active", "TER-2", WorkspaceState.ACTIVE),
            resource(ResourceKind.VOLUME, "abandoned", "TER-3", WorkspaceState.ABANDONED),
            resource(ResourceKind.VOLUME, "orphan", "TER-4", WorkspaceState.ORPHAN),
            resource(ResourceKind.VOLUME, "gone", "TER-5", null),
            resource(ResourceKind.VOLUME, "landed", "TER-6", WorkspaceState.LANDED),
        )
        assertEquals(Verdict.KEEP, v["active"])
        assertEquals(Verdict.CONFIRM, v["abandoned"])
        assertEquals(Verdict.CONFIRM, v["orphan"])
        assertEquals(Verdict.CONFIRM, v["gone"])
        assertEquals(Verdict.AUTO, v["landed"])
    }

    @Test
    fun `an adopted resource is never cleaned without confirmation, even of a landed workspace`() {
        val v = verdicts(resource(ResourceKind.VOLUME, "terrio-ter-7_data", "TER-7", WorkspaceState.LANDED, OwnershipClass.ADOPTED))
        assertEquals(Verdict.CONFIRM, v["terrio-ter-7_data"])
    }

    @Test
    fun `a stack that looks landed but is running or fresh is not cleaned by itself`() {
        val v = verdicts(
            resource(ResourceKind.CONTAINER, "web", "TER-8", WorkspaceState.LANDED, containerState = "running"),
            resource(ResourceKind.VOLUME, "data-8", "TER-8", WorkspaceState.LANDED),
            resource(ResourceKind.VOLUME, "fresh-9", "TER-9", WorkspaceState.LANDED, created = fresh),
        )
        assertEquals(Verdict.CONFIRM, v["web"])
        assertEquals(Verdict.CONFIRM, v["data-8"])
        assertEquals(Verdict.KEEP, v["fresh-9"])
    }

    @Test
    fun `a released workspace's resources go by themselves, labelled or adopted, active or not, running or not`() {
        val at = Instant.parse("2026-10-08T11:00:00Z")
        val released = ReconcilePlanner(ReconcileConfig(graceMinutes = 60), { repo, workspace -> at.takeIf { repo == "Terrio" && workspace == "TER-1" } }) { now }
        val plan = released.plan(
            listOf(
                resource(ResourceKind.CONTAINER, "web", "TER-1", WorkspaceState.ACTIVE, containerState = "running", created = old),
                resource(ResourceKind.VOLUME, "adopted-1", "TER-1", WorkspaceState.ACTIVE, OwnershipClass.ADOPTED),
                resource(ResourceKind.VOLUME, "young-1", "TER-1", WorkspaceState.LANDED, created = "2026-10-08T10:59:00.000Z"),
                resource(ResourceKind.VOLUME, "other-2", "TER-2", WorkspaceState.ACTIVE),
            ),
            registry(),
        )
        val byName = plan.associateBy { it.name }
        listOf("web", "adopted-1", "young-1").forEach {
            assertEquals(Verdict.AUTO, byName.getValue(it).verdict, it)
            assertTrue(byName.getValue(it).released, it)
        }
        assertEquals(Verdict.KEEP, byName.getValue("other-2").verdict)
        assertEquals(false, byName.getValue("other-2").released)
    }

    @Test
    fun `a release covers what existed when it was made, not a workspace of the same name made later, and never a protected resource`() {
        val at = Instant.parse("2026-10-08T11:00:00Z")
        val released = ReconcilePlanner(ReconcileConfig(protect = listOf(ProtectRule(Regex("^keep")))), { _, _ -> at }) { now }
        val v = released.plan(
            listOf(
                resource(ResourceKind.VOLUME, "before", "TER-1", WorkspaceState.ACTIVE, created = "2026-10-08T10:00:00.000Z"),
                resource(ResourceKind.VOLUME, "after", "TER-1", WorkspaceState.ACTIVE, created = "2026-10-08T11:30:00.000Z"),
                resource(ResourceKind.VOLUME, "keep-me", "TER-1", WorkspaceState.ACTIVE, created = "2026-10-08T10:00:00.000Z"),
            ),
            registry(),
        ).associate { it.name to it.verdict }
        assertEquals(Verdict.AUTO, v["before"])
        assertEquals(Verdict.KEEP, v["after"])
        assertEquals(Verdict.PROTECTED, v["keep-me"])
    }

    @Test
    fun `resources of a repository the registry does not know are kept`() {
        val v = verdicts(resource(ResourceKind.VOLUME, "other-repo", "X-1", null, repo = "Elsewhere"))
        assertEquals(Verdict.KEEP, v["other-repo"])
    }

    @Test
    fun `a protect rule wins over everything, by name or by compose project`() {
        val rules = arrayOf(ProtectRule(Regex("^terrio-importer(_|$)")), ProtectRule(Regex("^keepme"), setOf(ResourceKind.VOLUME)))
        val plan = planner(*rules).plan(
            listOf(
                resource(ResourceKind.VOLUME, "terrio-importer_terrio-postgres-data", "TER-1", WorkspaceState.LANDED),
                resource(ResourceKind.NETWORK, "default", "TER-1", WorkspaceState.LANDED).copy(project = "terrio-importer"),
                resource(ResourceKind.VOLUME, "keepme-1", "TER-1", WorkspaceState.LANDED),
                resource(ResourceKind.CONTAINER, "keepme-1", "TER-1", WorkspaceState.LANDED),
            ),
            registry(),
        )
        val byKey = plan.associate { it.key to it.verdict }
        assertEquals(Verdict.PROTECTED, byKey["volume:terrio-importer_terrio-postgres-data"])
        assertEquals(Verdict.PROTECTED, byKey["network:id-default"])
        assertEquals(Verdict.PROTECTED, byKey["volume:keepme-1"])
        assertEquals(Verdict.AUTO, byKey["container:id-keepme-1"], "a kinds-limited rule covers only those kinds")
    }

    @Test
    fun `unowned resources are not in the plan at all`() {
        val plan = planner().plan(listOf(resource(ResourceKind.VOLUME, "recserving-data", null, null, OwnershipClass.UNOWNED, repo = "")), registry())
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `an orphan directory needs confirmation and a protect rule keeps it`() {
        val dir = Workspace("C:/ws/terrio-worktrees/TER-77", "TER-77", "directory", WorkspaceState.ORPHAN, note = "not a worktree")
        val registered = Workspace("C:/ws/terrio-worktrees/TER-1", "TER-1", "worktree", WorkspaceState.ORPHAN, note = "directory is gone")
        val plan = planner().plan(emptyList(), registry(dir, registered))
        assertEquals(listOf("directory:C:/ws/terrio-worktrees/TER-77"), plan.map { it.key })
        assertEquals(Verdict.CONFIRM, plan.single().verdict)
        assertEquals(Verdict.PROTECTED, planner(ProtectRule(Regex("TER-77$"))).plan(emptyList(), registry(dir)).single().verdict)
    }
}
