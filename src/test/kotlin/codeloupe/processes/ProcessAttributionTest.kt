package codeloupe.processes

import codeloupe.TestRepos
import codeloupe.workspace.RepoWorkspaces
import codeloupe.workspace.Workspace
import codeloupe.workspace.WorkspaceList
import codeloupe.workspace.WorkspaceState
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessAttributionTest {
    private val root = TestRepos.tmpDir("attribution")
    private val main = root.resolve("Terrio").createDirectories()
    private val ter5 = root.resolve("terrio-worktrees").resolve("TER-5").createDirectories()
    private val ter50 = root.resolve("terrio-worktrees").resolve("TER-50").createDirectories()
    private val nested = main.resolve(".claude").resolve("worktrees").resolve("x").createDirectories()

    private fun workspace(path: java.nio.file.Path, name: String, role: String = "worktree", state: WorkspaceState = WorkspaceState.ACTIVE) =
        Workspace(path.toString().replace('\\', '/'), name, role, state)

    private val registry = WorkspaceList(
        "now",
        listOf(
            RepoWorkspaces(
                main.toString().replace('\\', '/'), "Terrio", "x/.git", "master", emptyList(), emptyMap(),
                listOf(workspace(main, "Terrio", "main"), workspace(ter5, "TER-5", state = WorkspaceState.LANDED), workspace(ter50, "TER-50"), workspace(nested, "x")),
            ),
        ),
    )

    private fun process(pid: Long, cwd: String?, line: String? = "java -jar app.jar", rss: Long = 100L * 1024 * 1024, name: String = "java") =
        ProcessInfo(pid, 1_000, name, line, cwd, rss, 10)

    private fun attributed(vararg processes: ProcessInfo) = ProcessAttribution.attribute(processes.toList(), registry, self = -1).associate { it.pid to "${it.workspace}/${it.via}" }

    @Test
    fun `a process belongs to the workspace whose directory holds its working directory, the deepest one`() {
        val result = attributed(
            process(1, ter5.toString()),
            process(2, ter5.resolve("module").resolve("build").toString() + "\\"),
            process(3, ter50.toString()),
            process(4, nested.resolve("src").toString()),
            process(5, main.resolve("docs").toString()),
            process(6, root.toString()),
            process(7, null, line = null),
        )
        assertEquals(mapOf(1L to "TER-5/cwd", 2L to "TER-5/cwd", 3L to "TER-50/cwd", 4L to "x/cwd", 5L to "Terrio/cwd"), result)
    }

    @Test
    fun `a process that works elsewhere but was started on a path of a workspace belongs to it by its command line, at a path boundary`() {
        val result = attributed(
            process(1, root.toString(), line = "node ${ter5.toString().replace('\\', '/')}/server.js"),
            process(2, root.toString(), line = "java -Dproject=\"${ter5}\" -jar x.jar"),
            process(3, root.toString(), line = "node ${ter5}x/server.js"),
            process(4, root.toString(), line = "node ${ter50}"),
        )
        assertEquals(mapOf(1L to "TER-5/command line", 2L to "TER-5/command line", 4L to "TER-50/command line"), result)
    }

    @Test
    fun `the report masks secrets in a command line, shortens it and counts the memory`() {
        val secret = "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"
        val entry = ProcessAttribution.attribute(
            listOf(process(1, ter5.toString(), line = "java -Dtoken=$secret -jar " + "x".repeat(2_000), rss = 300L * 1024 * 1024)), registry, self = -1,
        ).single()
        assertTrue(secret !in entry.commandLine)
        assertTrue(entry.commandLine.length <= 300)
        assertEquals(300, entry.rssMb)
        assertEquals(WorkspaceState.LANDED, entry.workspaceState)
        assertEquals(ProcessEntry.key(1, 1_000), entry.key)
    }

    @Test
    fun `the daemon's own process is never attributed`() {
        val result = ProcessAttribution.attribute(listOf(process(42, ter5.toString())), registry, self = 42)
        assertEquals(emptyList(), result)
    }
}
