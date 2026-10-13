package codeloupe.brain

import codeloupe.TestRepos
import codeloupe.config.WorkspacesConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The tracker project and code repository `dispatch_plan` reads from a brain workspace's `workspace.json`. */
class WorkspaceTasksTest {
    private fun workspace(json: String): Path = TestRepos.tmpDir("brainws").also {
        it.resolve("brain").createDirectories()
        it.resolve(BrainCli.SCRIPT).writeText("// engine")
        it.resolve("brain/workspace.json").writeText(json)
    }

    private fun config(dir: Path) = WorkspacesConfig(brains = listOf(dir.toString()))

    @Test
    fun `a workspace names its project from the task id pattern and its repository when that exists`() {
        val repo = TestRepos.tmpDir("canonical")
        val dir = workspace("""{"name":"w","taskIdPattern":"ws-\\d{1,6}","canonicalRepo":"${repo.toString().replace('\\', '/')}"}""")
        val scope = assertNotNull(WorkspaceTasks.scopeOf(config(dir), dir.resolve("sub").toString()))
        assertEquals(listOf("WS"), scope.projects)
        assertEquals(repo.toString().replace('\\', '/'), scope.codeRoot)
    }

    @Test
    fun `the task prefix names the project when there is no pattern and a missing repository is left out`() {
        val dir = workspace("""{"taskPrefix":"AB-","canonicalRepo":"${dir("gone")}"}""")
        val scope = assertNotNull(WorkspaceTasks.scopeOf(config(dir), dir.toString()))
        assertEquals(listOf("AB"), scope.projects)
        assertNull(scope.codeRoot)
    }

    @Test
    fun `a root outside every workspace, or a workspace that names no task ids, has no scope`() {
        val dir = workspace("""{"name":"w"}""")
        assertNull(WorkspaceTasks.scopeOf(config(dir), dir.toString()))
        assertNull(WorkspaceTasks.scopeOf(config(workspace("""{"taskPrefix":"AB-"}""")), TestRepos.tmpDir("elsewhere").toString()))
    }

    private fun dir(name: String) = TestRepos.tmpDir("missing").resolve(name).toString().replace('\\', '/').also { check(!Files.exists(Path.of(it))) }
}
