package codeloupe.accounts

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountResolverTest {
    private val userHome = Path.of("home").toAbsolutePath()
    private val fp = "0123456789ab"

    private fun account(id: String, dir: Path, implicit: Boolean = false, identity: String? = null) = ClaudeAccount(id, id, dir, implicit, identity = identity)

    private fun resolver(vararg accounts: ClaudeAccount) = AccountResolver(accounts.toList(), userHome)

    @Test
    fun `without an identity the location is all there is`() {
        assertEquals("a", resolver().resolve("a", null))
        assertNull(resolver().resolve(null, null))
    }

    @Test
    fun `an account that names the fingerprint gets the session wherever the transcript lies`() {
        val r = resolver(account("a", userHome.resolve(".claude"), identity = fp), account("b", userHome.resolve(".claude"), identity = "ba9876543210"))
        assertEquals("a", r.resolve(null, fp), "two accounts on one directory leave the location undecided, the identity decides")
        assertEquals("b", r.resolve("a", "ba9876543210"))
    }

    @Test
    fun `an identity two accounts both claim names neither`() {
        val r = resolver(account("a", userHome.resolve("x"), identity = fp), account("b", userHome.resolve("y"), identity = fp))
        assertEquals("identity-$fp", r.resolve(null, fp))
        assertEquals("a", r.resolve("a", fp), "the location of an account on a directory of its own stands")
    }

    @Test
    fun `an implicit account or the default directory is no proof, an own directory is`() {
        val r = resolver(account("default", userHome.resolve(".claude"), implicit = true), account("b", userHome.resolve("claude-b")))
        assertEquals("identity-$fp", r.resolve("default", fp))
        assertEquals("identity-$fp", r.resolve(null, fp))
        assertEquals("b", r.resolve("b", fp))
        assertEquals("default", r.resolve("default", null))

        val listed = resolver(account("default", userHome.resolve(".claude")))
        assertEquals("identity-$fp", listed.resolve("default", fp), "a listed account on ~/.claude shares it with other logins")
    }

    @Test
    fun `a session a desktop instance lists goes to its account before identity, own directory and the unknown`() {
        val accounts = arrayOf(account("a", userHome.resolve(".claude"), identity = fp), account("b", userHome.resolve(".claude")), account("own", userHome.resolve("claude-own")))
        val r = AccountResolver(accounts.toList(), userHome, desktop = mapOf("s-b" to "b", "s-own" to "b"))
        assertEquals("b", r.resolve(null, fp, "s-b"), "desktop over the identity an account names")
        assertEquals("b", r.resolve("own", null, "s-own"), "desktop over the account whose directory holds the transcript")
        assertEquals("a", r.resolve(null, fp, "other"), "a session the desktop does not list falls back to the identity")
        assertEquals("own", r.resolve("own", null, "other"), "then to the directory of its own")
        assertEquals("own", r.resolve("own", null), "no session id, no desktop")
        assertNull(r.resolve(null, null, "other"), "then to unknown")
    }

    @Test
    fun `an anonymous account is resolved again when its fingerprint changes or gets claimed`() {
        assertEquals("identity-$fp", resolver().resolve("identity-other0000000", fp))
        assertEquals("a", resolver(account("a", userHome.resolve("x"), identity = fp)).resolve("identity-$fp", fp))
    }
}
