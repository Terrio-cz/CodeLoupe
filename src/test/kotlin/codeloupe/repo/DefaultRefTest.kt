package codeloupe.repo

import codeloupe.TestRepos
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DefaultRefTest {
    private val repo = TestRepos.fixtureRepo("kotlin/sample")
    private val commonDir = repo.resolve(".git").toString().replace('\\', '/')

    @Test
    fun `the repository names its base branch, but never as an option for git`() {
        repo.resolve(".codeloupe.json").writeText("""{"baseBranch":"release/1.2"}""")
        assertEquals("release/1.2", DefaultRef.of(commonDir))
        repo.resolve(".codeloupe.json").writeText("""{"baseBranch":"--output=/tmp/x"}""")
        assertFalse(DefaultRef.of(commonDir).startsWith("-"), "the file's value is dropped")
        repo.resolve(".codeloupe.json").writeText("""{"baseBranch":"main\nother"}""")
        assertFalse('\n' in DefaultRef.of(commonDir))
    }
}
