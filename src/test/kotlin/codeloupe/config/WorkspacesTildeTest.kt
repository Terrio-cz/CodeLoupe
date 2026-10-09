package codeloupe.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspacesTildeTest {
    private val home = System.getProperty("user.home").trimEnd('/', '\\')

    private fun parse(text: String) = WorkspacesConfig.parse(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun `a tilde in the paths of the workspaces section means the home of the user`() {
        val config = parse("""{"workspaces":{"repos":["~/code/app",{"path":"~/code/lib","roots":["~/trees","/abs/trees"]}],"gradleUserHome":"~/.gradle-alt"}}""")
        assertEquals(
            listOf(WorkspacesConfig.Repo("$home/code/app"), WorkspacesConfig.Repo("$home/code/lib", listOf("$home/trees", "/abs/trees"))),
            config.repos,
        )
        assertEquals("$home/.gradle-alt", config.gradleUserHome)
    }

    @Test
    fun `a tilde inside a name is not a home`() {
        assertEquals(listOf(WorkspacesConfig.Repo("/a/~b")), parse("""{"workspaces":{"repos":["/a/~b"]}}""").repos)
    }
}
