package codeloupe.accounts

import codeloupe.TestRepos
import codeloupe.metrics.AccountIdentity
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeLoginTest {
    private val userHome = TestRepos.tmpDir("login-home")
    private val uuid = "0b9a6c1e-1111-4222-8333-444455556666"

    @Test
    fun `the default directory takes its login from the file beside it, not from a stale one inside`() {
        LoginFiles.write(userHome.resolve(".claude.json"), uuid)
        LoginFiles.write(userHome.resolve(".claude").resolve(".claude.json"), null)
        assertEquals(AccountIdentity.fingerprint(uuid), ClaudeLogin(userHome).fingerprintOf(userHome.resolve(".claude")))
    }

    @Test
    fun `another directory takes its login from the file inside it`() {
        val other = TestRepos.tmpDir("login-other")
        LoginFiles.write(userHome.resolve(".claude.json"), "default-uuid")
        LoginFiles.write(other.resolve(".claude.json"), uuid)
        assertEquals(AccountIdentity.fingerprint(uuid), ClaudeLogin(userHome).fingerprintOf(other))
    }

    @Test
    fun `a missing, damaged or anonymous file names no login`() {
        val login = ClaudeLogin(userHome)
        val dir = TestRepos.tmpDir("login-none")
        assertNull(login.fingerprintOf(dir))
        Files.writeString(dir.resolve(".claude.json"), "{ not json")
        assertNull(login.fingerprintOf(dir))
        LoginFiles.write(dir.resolve(".claude.json"), null)
        assertNull(login.fingerprintOf(dir))
    }

    @Test
    fun `a changed file is read again`() {
        val dir = TestRepos.tmpDir("login-change")
        val file = LoginFiles.write(dir.resolve(".claude.json"), "first")
        val login = ClaudeLogin(userHome)
        assertEquals(AccountIdentity.fingerprint("first"), login.fingerprintOf(dir))
        LoginFiles.write(file, "second-longer")
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 10_000))
        assertEquals(AccountIdentity.fingerprint("second-longer"), login.fingerprintOf(dir))
    }
}
