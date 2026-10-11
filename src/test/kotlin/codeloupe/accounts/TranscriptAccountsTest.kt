package codeloupe.accounts

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptAccountsTest {
    private fun account(id: String, dir: String) = ClaudeAccount(id, id, Path.of(dir).toAbsolutePath(), implicit = false)

    private fun transcript(dir: String, project: String = "proj") = Path.of(dir).toAbsolutePath().resolve("projects").resolve(project).resolve("s.jsonl")

    @Test
    fun `the account is the one whose projects folder holds the transcript`() {
        val accounts = TranscriptAccounts(listOf(account("a", "cfg/a"), account("b", "cfg/b")))
        assertEquals("a", accounts.of(transcript("cfg/a")))
        assertEquals("b", accounts.of(transcript("cfg/b")))
        assertEquals("b", accounts.of(transcript("cfg/b", "x").resolveSibling("sub").resolve("../s.jsonl")), "a path with .. is normalised first")
    }

    @Test
    fun `a directory no account owns, a sibling that only shares a prefix, or no accounts at all give none`() {
        val accounts = TranscriptAccounts(listOf(account("a", "cfg/a")))
        assertNull(accounts.of(transcript("cfg/other")))
        assertNull(accounts.of(transcript("cfg/a-copy")), "cfg/a-copy is not under cfg/a")
        assertNull(TranscriptAccounts(emptyList()).of(transcript("cfg/a")))
    }

    @Test
    fun `two accounts on one config directory give none rather than a guess, one account listed twice still gives it`() {
        assertNull(TranscriptAccounts(listOf(account("a", "cfg/x"), account("b", "cfg/x"))).of(transcript("cfg/x")))
        assertEquals("a", TranscriptAccounts(listOf(account("a", "cfg/x"), account("a", "cfg/x"))).of(transcript("cfg/x")))
    }
}
