package codeloupe.config

import codeloupe.TestRepos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepoConfigTest {
    private val home = TestRepos.tmpDir("repo-config")
    private val file = home.resolve("config.json")
    private val app = TestRepos.tmpDir("repo-a")
    private val lib = TestRepos.tmpDir("repo-b")
    private val fresh = TestRepos.tmpDir("repo-c")

    private fun slashed(path: Path) = path.toAbsolutePath().normalize().toString().replace('\\', '/')

    private fun parsed() = WorkspacesConfig.parse(Json.parseToJsonElement(Files.readString(file)).jsonObject)

    @Test
    fun `adding to a home without a configuration creates it and the daemon's loader reads it back`() {
        val result = RepoConfig.add(home, listOf(app, lib))
        assertEquals(listOf(slashed(app), slashed(lib)), result.added)
        assertEquals(listOf(slashed(app), slashed(lib)), parsed().repos.map { it.path })
        assertEquals(RepoConfig.list(home), parsed().repos.map { it.path })
    }

    @Test
    fun `other keys and object entries stay, a repository already listed is not added again in any spelling`() {
        Files.writeString(
            file,
            """{ "port": 47999, "workspaces": { "abandonedDays": 30, "repos": [ { "path": "${slashed(app)}", "roots": ["D:/trees"] }, "${slashed(lib)}" ] }, "budgets": { "rssMb": 200 } }""",
        )
        val result = RepoConfig.add(home, listOf(Path.of(app.toString().uppercase()), lib, fresh))
        assertEquals(listOf(slashed(fresh)), result.added)
        assertEquals(2, result.already.size)
        val written = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals("47999", written["port"].toString())
        assertEquals("""{"rssMb":200}""", written["budgets"].toString())
        val config = parsed()
        assertEquals(30, config.abandonedDays)
        assertEquals(listOf("D:/trees"), config.repos.first().roots, "an entry with roots is kept as it was")
        assertEquals(3, config.repos.size)
    }

    @Test
    fun `a configuration that is not JSON is never rewritten`() {
        Files.writeString(file, "{ // my notes\n \"port\": 1 }")
        val before = Files.readString(file)
        assertFailsWith<IllegalStateException> { RepoConfig.add(home, listOf(app)) }
        assertEquals(before, Files.readString(file))
        assertTrue(runCatching { RepoConfig.list(home) }.isFailure)
    }

    @Test
    fun `nothing new means no write`() {
        RepoConfig.add(home, listOf(app))
        val stamp = Files.getLastModifiedTime(file)
        Thread.sleep(20)
        RepoConfig.add(home, listOf(app))
        assertEquals(stamp, Files.getLastModifiedTime(file))
    }
}
