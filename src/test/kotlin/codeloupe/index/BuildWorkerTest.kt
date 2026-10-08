package codeloupe.index

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A query waits for the first build of a repository, so only updates run at lowered priority (CL-80). */
class BuildWorkerTest {
    @Test
    fun `first build keeps normal priority, updates yield`() {
        assertFalse(BuildWorker.isBackground(arrayOf("repo", "abc123", "out.db")))
        assertTrue(BuildWorker.isBackground(arrayOf(BuildWorker.UPDATE, "repo", "abc123", "db", "update.json")))
        assertTrue(BuildWorker.isBackground(arrayOf(BuildWorker.UPDATE, "repo", BuildWorker.NO_COMMIT, "db", "update.json")))
    }
}
