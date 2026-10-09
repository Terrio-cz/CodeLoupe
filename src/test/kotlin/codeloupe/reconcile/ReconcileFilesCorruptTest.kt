package codeloupe.reconcile

import codeloupe.TestRepos
import codeloupe.config.ReconcileConfig
import codeloupe.workspace.WorkspaceRef
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two small files of the reconciler are written by the daemon, but a hand edit or a half-written disk must not stop it from starting. */
class ReconcileFilesCorruptTest {
    private val home = TestRepos.tmpDir("corrupt")
    private val now = Instant.parse("2026-10-08T12:00:00Z")

    @Test
    fun `a release with a time that cannot be read is dropped, the others are kept`() {
        val file = home.resolve("releases.json")
        Files.writeString(file, """[{"repo":"Terrio","workspace":"TER-1","at":"yesterday"},{"repo":"Terrio","workspace":"TER-2","at":"2026-10-08T11:00:00Z"}]""")
        val store = ReleaseStore(file) { now }
        assertNull(store.releasedAt("Terrio", "TER-1"))
        assertEquals(listOf("TER-2"), store.all().map { it.workspace })
        store.mark(WorkspaceRef("Terrio", "TER-3"))
        assertEquals(listOf("TER-2", "TER-3"), ReleaseStore(file) { now }.all().map { it.workspace })
    }

    @Test
    fun `a wait that cannot be read is over`() {
        val file = home.resolve("state.json")
        Files.writeString(file, """{"volume:v":{"attempts":3,"nextAttempt":"soon","lastError":"locked"}}""")
        val state = ReconcileState(file, ReconcileConfig()) { now }
        assertTrue(state.due("volume:v"))
        assertTrue(state.anyDue())
    }
}
