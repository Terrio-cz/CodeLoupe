package codeloupe.secrets

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every backend wraps and unwraps a key; the OS ones run where their key store exists (the CI matrix has all three). */
class KeyProtectorTest {
    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun `the passphrase backend round-trips and refuses another passphrase`() {
        val protector = PassphraseProtector("one passphrase".toCharArray(), iterations = 1_000)
        val blob = protector.wrap(key)
        assertContentEquals(key, protector.unwrap(blob))
        assertTrue("one passphrase" !in blob)
        assertFailsWith<IllegalStateException> { PassphraseProtector("another".toCharArray(), iterations = 1_000).unwrap(blob) }
        assertTrue(protector.wrap(key) != blob, "a fresh salt and nonce each time")
    }

    @Test
    fun `windows dpapi wraps for the current user`() {
        assumeTrue(DpapiProtector.available(), "not Windows")
        val protector = DpapiProtector()
        val blob = protector.wrap(key)
        assertTrue(blob.length > 40 && java.util.Base64.getEncoder().encodeToString(key) !in blob)
        assertContentEquals(key, protector.unwrap(blob))
    }

    @Test
    fun `macos keychain keeps the key in the login keychain`() {
        assumeTrue(KeychainProtector.available(), "no usable macOS keychain")
        val protector = KeychainProtector("codeloupe-test")
        val blob = protector.wrap(key)
        try {
            assertContentEquals(key, protector.unwrap(blob))
        } finally {
            protector.forget(blob)
        }
    }

    @Test
    fun `linux libsecret keeps the key in the secret service`() {
        assumeTrue(LibsecretProtector.available(), "no usable secret service")
        val protector = LibsecretProtector("codeloupe-test")
        val blob = protector.wrap(key)
        try {
            assertContentEquals(key, protector.unwrap(blob))
        } finally {
            protector.forget(blob)
        }
    }

    @Test
    fun `the choice follows what a vault was made with and falls back to the passphrase variable`() {
        val withPass = mapOf(KeyProtectors.PASSPHRASE_VARIABLE to "pw")
        assertEquals("passphrase", KeyProtectors.choose(withPass, wrapped = "passphrase").name)
        assertFailsWith<IllegalStateException> { KeyProtectors.choose(emptyMap(), wrapped = "passphrase") }
        assertFailsWith<IllegalStateException> { KeyProtectors.choose(withPass, wrapped = "rot13") }
        assertEquals("dpapi", KeyProtectors.choose(emptyMap(), wrapped = "dpapi").name)
    }
}
