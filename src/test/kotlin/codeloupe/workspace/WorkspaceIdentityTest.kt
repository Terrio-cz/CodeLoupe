package codeloupe.workspace

import codeloupe.docker.Ownership
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceIdentityTest {
    private fun workspace(path: String, name: String, taskId: String? = null) = Workspace(path, name, "worktree", WorkspaceState.ACTIVE, taskId = taskId)

    private val list = WorkspaceList(
        "now",
        listOf(
            RepoWorkspaces(
                "C:/ws/Terrio", "Terrio", "C:/ws/Terrio/.git", "main", emptyList(), emptyMap(),
                listOf(workspace("C:/ws/Terrio", "Terrio").copy(role = "main"), workspace("C:/ws/terrio-worktrees/TER-12", "TER-12", "TER-12")),
            ),
        ),
    )

    @Test
    fun `a directory inside a worktree belongs to that worktree`() {
        assertEquals(Ownership("Terrio", "TER-12", "TER-12"), WorkspaceIdentity.pick(list, Path.of("C:/ws/terrio-worktrees/TER-12/src/main")))
        assertEquals(Ownership("Terrio", "TER-12", "TER-12"), WorkspaceIdentity.pick(list, Path.of("C:/ws/terrio-worktrees/TER-12")))
    }

    @Test
    fun `the main worktree has no task`() {
        assertEquals(Ownership("Terrio", "Terrio", ""), WorkspaceIdentity.pick(list, Path.of("C:/ws/Terrio/app")))
    }

    @Test
    fun `a directory that only shares a name prefix is no part of the workspace`() {
        assertFailsWith<IllegalArgumentException> { WorkspaceIdentity.pick(list, Path.of("C:/ws/terrio-worktrees/TER-120")) }
        assertFailsWith<IllegalArgumentException> { WorkspaceIdentity.pick(list, Path.of("C:/elsewhere")) }
    }
}
