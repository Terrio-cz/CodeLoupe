package codeloupe.docker

import codeloupe.TestRepos
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A container inherits the labels of its image, so the labels alone do not prove that CodeLoupe made it. */
class InstallLabelTest {
    private val mine = "a".repeat(32)
    private val labels = mapOf(Ownership.REPO to "App", Ownership.WORKSPACE to "TER-1", Ownership.TASK to "TER-1")
    private val classifier = ResourceClassifier(emptyList(), mine) { _, _ -> WorkspaceState.LANDED }

    private fun obj(kind: ResourceKind, name: String, extra: Map<String, String>) = DockerObject(kind, "id-$name", listOf(name), labels + extra)

    private fun owner(kind: ResourceKind, extra: Map<String, String>) = classifier.classify(listOf(obj(kind, "x", extra))).single().ownership

    @Test
    fun `a container is ours only with this installation's id`() {
        assertEquals(OwnershipClass.OWNED, owner(ResourceKind.CONTAINER, mapOf(Ownership.INSTALL to mine)))
        assertEquals(OwnershipClass.UNOWNED, owner(ResourceKind.CONTAINER, emptyMap()), "labels an image brought along")
        assertEquals(OwnershipClass.UNOWNED, owner(ResourceKind.CONTAINER, mapOf(Ownership.INSTALL to "b".repeat(32))))
    }

    @Test
    fun `a volume, network or image made before the id existed is still ours, one of another installation is not`() {
        for (kind in listOf(ResourceKind.VOLUME, ResourceKind.NETWORK, ResourceKind.IMAGE)) {
            assertEquals(OwnershipClass.OWNED, owner(kind, emptyMap()), kind.name)
            assertEquals(OwnershipClass.OWNED, owner(kind, mapOf(Ownership.INSTALL to mine)), kind.name)
            assertEquals(OwnershipClass.UNOWNED, owner(kind, mapOf(Ownership.INSTALL to "b".repeat(32))), kind.name)
        }
    }

    @Test
    fun `without an id the labels decide, as before`() {
        val plain = ResourceClassifier(emptyList()) { _, _ -> null }
        assertEquals(OwnershipClass.OWNED, plain.classify(listOf(obj(ResourceKind.CONTAINER, "x", emptyMap()))).single().ownership)
    }

    @Test
    fun `the labels CodeLoupe writes carry the id and read back, and an old volume is still the same workspace`() {
        val written = Ownership("App", "TER-1", "TER-1", mine)
        assertEquals(mine, written.labels()[Ownership.INSTALL])
        assertEquals(written, Ownership.of(written.labels()))
        assertNull(Ownership("App", "TER-1").labels()[Ownership.INSTALL])
        assertTrue(written.sameWorkspace(Ownership.of(labels)!!))
        assertNotEquals(written, Ownership.of(labels))
    }

    @Test
    fun `the id is made once, kept, and is not guessable text`() {
        val home = TestRepos.tmpDir("install")
        val id = InstallId.of(home)
        assertTrue(Regex("[0-9a-f]{32}").matches(id), id)
        assertEquals(id, InstallId.of(home))
        assertNotEquals(id, InstallId.of(TestRepos.tmpDir("install-other")))
    }

    @Test
    fun `two repositories with one name and different states leave the state unknown instead of letting one decide for the other`() = runBlocking {
        fun ws(state: WorkspaceState) = Workspace("C:/x/TER-1", "TER-1", "worktree", state)
        fun repo(path: String, state: WorkspaceState) = RepoWorkspaces(path, "App", "$path/.git", "main", emptyList(), emptyMap(), listOf(ws(state)))
        val list = WorkspaceList("now", listOf(repo("C:/a/App", WorkspaceState.LANDED), repo("C:/b/App", WorkspaceState.ACTIVE)))
        val container = """[{"Id":"c1","Names":["/c1"],"State":"exited","Created":1,"Labels":{"codeloupe.repo":"App","codeloupe.workspace":"TER-1","codeloupe.install":"$mine"},"Ports":[]}]"""
        FakeDockerEngine { _, path ->
            when {
                path == "/version" -> 200 to """{"Version":"27.0"}"""
                path.startsWith("/containers/json") -> 200 to container
                path.startsWith("/volumes") -> 200 to """{"Volumes":[]}"""
                else -> 200 to "[]"
            }
        }.use { engine ->
            val report = ResourceInventory(emptyList(), { list }, { engine.api }, installId = { mine }).report()
            val entry = report.resources.single()
            assertEquals(OwnershipClass.OWNED, entry.ownership)
            assertNull(entry.workspaceState)
            val agreeing = WorkspaceList("now", listOf(repo("C:/a/App", WorkspaceState.LANDED), repo("C:/b/App", WorkspaceState.LANDED)))
            assertEquals(WorkspaceState.LANDED, ResourceInventory(emptyList(), { agreeing }, { engine.api }, installId = { mine }).report().resources.single().workspaceState)
        }
    }
}
