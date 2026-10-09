package codeloupe.taskcode

import codeloupe.TestRepos
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskPatternTest {
    private fun repoWith(config: String) = TestRepos.tmpDir("task-pattern").also { Files.writeString(it.resolve(".codeloupe.json"), config) }

    @Test
    fun `a pattern of the repository that never finishes is cut off and then finds nothing`() {
        val pattern = TaskPattern.of(repoWith("""{"taskPattern":"(.*a){14}"}"""), emptyList())
        val subject = "TER-1 " + "a".repeat(34) + "b"
        val started = System.nanoTime()
        assertEquals(emptyList(), pattern.idsIn(subject))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "the first call ends within its budget")
        val again = System.nanoTime()
        assertEquals(emptyList(), pattern.idsIn(subject))
        assertTrue((System.nanoTime() - again) / 1_000_000 < 100, "the next ones do not try again")
    }

    @Test
    fun `an ordinary pattern of the repository still finds the ids`() {
        val pattern = TaskPattern.of(repoWith("""{"taskPattern":"WORK-\\d+"}"""), emptyList())
        assertEquals(listOf("WORK-12"), pattern.idsIn("fix WORK-12 and more"))
        assertTrue(pattern.isId("WORK-3"))
    }
}
