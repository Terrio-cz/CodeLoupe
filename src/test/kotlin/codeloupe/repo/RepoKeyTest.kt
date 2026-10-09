package codeloupe.repo

import codeloupe.TestRepos
import codeloupe.platform.Sha1
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RepoKeyTest {
    private val repos = TestRepos.tmpDir("repo-key")

    private fun legacy(commonDir: String) = Sha1.hex(commonDir.lowercase()).take(12)

    private fun record(id: String, commonDir: String): Path =
        repos.resolve(id).createDirectories().resolve("repo.json").also { it.writeText("""{"id":"$id","commonDir":"$commonDir"}""") }

    @Test
    fun `where case is ignored two spellings share an index, as they always did`() {
        assertEquals(RepoKey.id("/src/Foo/.git", insensitive = true), RepoKey.id("/src/foo/.git", insensitive = true))
        assertEquals(legacy("/src/Foo/.git"), RepoKey.resolve(repos, "/src/Foo/.git", insensitive = true))
        assertEquals(RepoKey.resolve(repos, "/src/foo/.git", insensitive = true), RepoKey.resolve(repos, "/src/Foo/.git", insensitive = true))
    }

    @Test
    fun `where case matters two spellings have two indexes`() {
        assertNotEquals(RepoKey.resolve(repos, "/src/Foo/.git", insensitive = false), RepoKey.resolve(repos, "/src/foo/.git", insensitive = false))
    }

    @Test
    fun `an index made under the folded key is kept by the repository it names, not re-made`() {
        val id = legacy("/src/Foo/.git")
        record(id, "/src/Foo/.git")
        assertEquals(id, RepoKey.resolve(repos, "/src/Foo/.git", insensitive = false))
    }

    @Test
    fun `a folded index that names the other spelling is left to it`() {
        val id = legacy("/src/foo/.git")
        record(id, "/src/foo/.git")
        val upper = RepoKey.resolve(repos, "/src/Foo/.git", insensitive = false)
        assertNotEquals(id, upper)
        assertEquals(id, RepoKey.resolve(repos, "/src/foo/.git", insensitive = false))
    }

    @Test
    fun `an all-lowercase repository whose only directory was written by its upper-case twin gets a directory of its own`() {
        val shared = legacy("/src/Foo/.git")
        record(shared, "/src/Foo/.git")
        val lower = RepoKey.resolve(repos, "/src/foo/.git", insensitive = false)
        assertNotEquals(shared, lower)
        assertEquals(shared, RepoKey.resolve(repos, "/src/Foo/.git", insensitive = false))
        record(lower, "/src/foo/.git")
        assertEquals(lower, RepoKey.resolve(repos, "/src/foo/.git", insensitive = false))
    }

    @Test
    fun `a directory without a readable record is not anybody's`() {
        val id = RepoKey.id("/src/Foo/.git", insensitive = false)
        Files.createDirectories(repos.resolve(id))
        assertEquals(id, RepoKey.resolve(repos, "/src/Foo/.git", insensitive = false))
    }
}
