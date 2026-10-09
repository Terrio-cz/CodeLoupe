package codeloupe.secrets

import codeloupe.TestRepos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** How the daemon opens the vault of its home: the way the vault was made, with what the daemon's environment holds. */
class SecretAccessTest {
    private val chain = SecretStore.chain()
    private val home = TestRepos.tmpDir("access")

    private fun vaultFile(): Path = home.resolve("secrets").resolve("vault.env")

    @Test
    fun `a vault made with a passphrase is opened with the passphrase variable and without it the reason names the variable`() {
        SecretStore(vaultFile(), PassphraseProtector("one passphrase".toCharArray())).set("TOKEN_A", SecretScope.GLOBAL, "fake-value-1")

        val opened = SecretAccess(home, env = mapOf(KeyProtectors.PASSPHRASE_VARIABLE to "one passphrase"))
        assertEquals("fake-value-1", opened.store?.resolve(chain)?.get("TOKEN_A")?.value)
        assertNull(opened.problem)

        val closed = SecretAccess(home, env = emptyMap())
        assertNull(closed.store)
        assertContains(assertNotNull(closed.problem), KeyProtectors.PASSPHRASE_VARIABLE)
    }

    @Test
    fun `a vault made with the OS key store is opened again by another access without a passphrase`() {
        val available = DpapiProtector.available() || KeychainProtector.available() || LibsecretProtector.available()
        assumeTrue(available, "no OS key store here (DPAPI, Keychain, libsecret)")
        SecretStore.open(home, emptyMap()).set("TOKEN_B", SecretScope.GLOBAL, "fake-value-2")
        try {
            assertEquals("fake-value-2", SecretAccess(home, env = emptyMap()).store?.resolve(chain)?.get("TOKEN_B")?.value)
        } finally {
            forgetKey()
        }
    }

    // The key of the vault stays in the OS store after the test; the blob in the vault file names it.
    private fun forgetKey() {
        val vault = Json.parseToJsonElement(Files.readString(vaultFile())).jsonObject
        val blob = vault.getValue("wrappedKey").jsonPrimitive.content
        when (vault.getValue("protector").jsonPrimitive.content) {
            "keychain" -> KeychainProtector().forget(blob)
            "libsecret" -> LibsecretProtector().forget(blob)
        }
    }
}
