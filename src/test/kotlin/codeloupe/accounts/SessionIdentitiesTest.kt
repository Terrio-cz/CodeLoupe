package codeloupe.accounts

import codeloupe.TestRepos
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionIdentitiesTest {
    private val file = TestRepos.tmpDir("session-identities").resolve(SessionIdentities.FILE)

    @Test
    fun `a stamp is kept across instances and the newest line of a session wins`() {
        val writer = SessionIdentities(file)
        writer.record("s1", "aaaaaaaaaaaa")
        writer.record("s2", "bbbbbbbbbbbb")
        writer.record("s1", "cccccccccccc")
        assertEquals(mapOf("s1" to "cccccccccccc", "s2" to "bbbbbbbbbbbb"), SessionIdentities(file).all())
    }

    @Test
    fun `an unchanged stamp is not written again and the view stays the same instance until the file changes`() {
        val store = SessionIdentities(file)
        store.record("s1", "aaaaaaaaaaaa")
        val size = Files.size(file)
        store.record("s1", "aaaaaaaaaaaa")
        assertEquals(size, Files.size(file))
        assertSame(store.all(), store.all())

        val reader = SessionIdentities(file)
        val first = reader.all()
        store.record("s2", "bbbbbbbbbbbb")
        assertEquals(2, reader.all().size, "a stamp written by another instance is seen")
        assertEquals(1, first.size)
    }

    @Test
    fun `damaged lines are skipped, a missing file means no stamps`() {
        assertTrue(SessionIdentities(file).all().isEmpty())
        Files.writeString(file, "not json\n{\"s\":\"s1\",\"i\":\"aaaaaaaaaaaa\",\"t\":1}\n{\"s\":\n")
        assertEquals(mapOf("s1" to "aaaaaaaaaaaa"), SessionIdentities(file).all())
    }

    @Test
    fun `a large file is rewritten without the sessions older than the retention`() {
        var clock = 0L
        val store = SessionIdentities(file) { clock }
        store.record("old", "aaaaaaaaaaaa")
        clock = 200L * 24 * 3_600_000
        Files.writeString(file, "x".repeat(600 * 1024) + "\n", StandardOpenOption.APPEND)
        store.record("new", "bbbbbbbbbbbb")
        assertEquals(mapOf("new" to "bbbbbbbbbbbb"), SessionIdentities(file).all())
        assertTrue(Files.size(file) < 1024)
    }

    @Test
    fun `an unwritable location loses the stamp and fails nothing`() {
        val blocked = TestRepos.tmpDir("session-identities-blocked").resolve("file")
        Files.writeString(blocked, "x")
        val store = SessionIdentities(blocked.resolve(SessionIdentities.FILE))
        store.record("s1", "aaaaaaaaaaaa")
        assertTrue(store.all().isEmpty())
    }
}
