package codeloupe.accounts

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSessionsTest {
    private val a = DesktopProfile()
    private val b = DesktopProfile()
    private val accounts = listOf(a.account("a"), b.account("b"))

    @Test
    fun `a session belongs to the account whose desktop directory lists it`() {
        a.session("11111111-aaaa-4000-8000-000000000001")
        a.session("11111111-aaaa-4000-8000-000000000002")
        b.session("22222222-bbbb-4000-8000-000000000001")
        assertEquals(
            mapOf("11111111-aaaa-4000-8000-000000000001" to "a", "11111111-aaaa-4000-8000-000000000002" to "a", "22222222-bbbb-4000-8000-000000000001" to "b"),
            DesktopSessions().sessions(accounts),
        )
    }

    @Test
    fun `an account without a desktop directory, a missing one and files that are not session files list nothing`() {
        a.session("s-1")
        a.write("notes.json", """{"cliSessionId":"s-2"}""")
        a.write("local_s-3.txt", """{"cliSessionId":"s-3"}""")
        val without = ClaudeAccount("c", "c", a.dir.resolveSibling("cfg-c"), implicit = false)
        val missing = ClaudeAccount("d", "d", a.dir.resolveSibling("cfg-d"), implicit = false, desktopDir = a.dir.resolveSibling("does-not-exist"))
        assertEquals(mapOf("s-1" to "a"), DesktopSessions().sessions(listOf(without, missing, a.account("a"))))
        assertEquals(emptyMap(), DesktopSessions().sessions(emptyList()))
    }

    @Test
    fun `a damaged file, an empty one and one without an id attribute nothing and break nothing`() {
        a.write("local_broken.json", "{ this is not json")
        a.write("local_empty.json", "")
        a.write("local_noid.json", """{"sessionId":"local_noid","cwd":"C:/x"}""")
        a.write("local_blank.json", """{"cliSessionId":""}""")
        a.write("local_number.json", """{"cliSessionId":42}""")
        a.session("good-1")
        assertEquals(mapOf("good-1" to "a"), DesktopSessions().sessions(accounts))
    }

    @Test
    fun `only the id key counts, not the text of a title that spells it`() {
        a.write("local_title.json", """{"sessionId":"local_title","title":"say \"cliSessionId\": \"evil-1\" now","cliSessionId":"real-1"}""")
        assertEquals(mapOf("real-1" to "a"), DesktopSessions().sessions(accounts))
    }

    @Test
    fun `nothing past the start of a file is read`() {
        // The id sits after the head, so a reader that took the whole file would find it; this one does not look there.
        a.write("local_late.json", """{"sessionId":"local_late","pad":"${"x".repeat(5000)}","cliSessionId":"late-1"}""")
        assertEquals(emptyMap(), DesktopSessions().sessions(accounts))
    }

    @Test
    fun `a file is read again only when it changes, and a deleted one is forgotten`() {
        val sessions = DesktopSessions()
        val file = a.session("s-1", mtimeMs = 1_000_000)
        assertEquals(mapOf("s-1" to "a"), sessions.sessions(accounts))

        // Same size, same time: the cached id stands even though the bytes differ.
        Files.writeString(file, Files.readString(file).replace("s-1", "s-9"))
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_000_000))
        assertEquals(mapOf("s-1" to "a"), sessions.sessions(accounts))

        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(2_000_000))
        assertEquals(mapOf("s-9" to "a"), sessions.sessions(accounts))

        Files.delete(file)
        assertTrue(sessions.sessions(accounts).isEmpty())
    }

    @Test
    fun `a session two accounts list goes to the newer file, and to none when they are equally new`() {
        a.session("shared-1", name = "local_a", mtimeMs = 1_000_000)
        b.session("shared-1", name = "local_b", mtimeMs = 2_000_000)
        a.session("tie-1", name = "local_a2", mtimeMs = 5_000_000)
        b.session("tie-1", name = "local_b2", mtimeMs = 5_000_000)
        assertEquals(mapOf("shared-1" to "b"), DesktopSessions().sessions(accounts))
    }
}
