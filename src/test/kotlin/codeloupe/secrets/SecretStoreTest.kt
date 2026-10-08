package codeloupe.secrets

import codeloupe.TestRepos
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The vault with a passphrase protector (the one backend every OS has): at-rest encryption, scopes, metadata, binding. */
class SecretStoreTest {
    private val home = TestRepos.tmpDir("vault")
    private val file = home.resolve("secrets").resolve("vault.env")
    private var now = Instant.parse("2026-10-08T12:00:00Z")
    private val protector = PassphraseProtector("correct horse".toCharArray(), iterations = 1_000)
    private val store = SecretStore(file, protector) { now }

    private fun later() = run { now = now.plusSeconds(3_600) }

    @Test
    fun `values are encrypted at rest and the key is not written in the clear`() {
        store.set("YOUTRACK_TOKEN", SecretScope.GLOBAL, "perm:abcDEF-1234567890-secretvalue")
        store.set("RESEND_KEY", SecretScope.workspace("C:/Work/Terrio"), "re_AnotherSecretValue99")
        val text = Files.readString(file)
        val bytes = Files.readAllBytes(file)
        for (value in listOf("perm:abcDEF-1234567890-secretvalue", "re_AnotherSecretValue99")) {
            assertFalse(value in text, "plain value in the vault file")
            assertFalse(Base64.getEncoder().encodeToString(value.toByteArray()) in text, "base64 of the value in the file")
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains(value))
        }
        // The data key lives only inside the passphrase-wrapped blob.
        val wrapped = Regex("\"wrappedKey\":\"([^\"]+)\"").find(text)!!.groupValues[1]
        assertTrue(wrapped.startsWith("pbkdf2:1000:"))
        val key = protector.unwrap(wrapped)
        assertFalse(Base64.getEncoder().encodeToString(key) in text.replace(wrapped, ""), "the data key in the clear")
        assertFalse(key.joinToString("") { "%02x".format(it) } in text)
    }

    @Test
    fun `scopes resolve global then workspace then repository, the narrowest wins`() {
        store.set("API_URL", SecretScope.GLOBAL, "https://global.example")
        store.set("API_URL", SecretScope.workspace("C:\\Work\\Terrio\\"), "https://workspace.example")
        store.set("API_URL", SecretScope.repository("terrio-importer"), "https://repo.example")
        store.set("ONLY_GLOBAL", SecretScope.GLOBAL, "global-only-value")
        store.set("OTHER_WS", SecretScope.workspace("elsewhere"), "not-visible-here")
        fun url(workspace: String?, repo: String?) = store.resolve(SecretStore.chain(workspace, repo)).getValue("API_URL").value
        assertEquals("https://global.example", url(null, null))
        assertEquals("https://workspace.example", url("c:/work/terrio", null), "ids compare with / and lower case")
        assertEquals("https://repo.example", url("C:/Work/Terrio", "terrio-importer"))
        assertEquals("https://global.example", url("C:/Work/Terrio/other", "no-such-repo"))
        val names = store.resolve(SecretStore.chain("C:/Work/Terrio", null)).keys
        assertEquals(setOf("API_URL", "ONLY_GLOBAL"), names)
        assertEquals(listOf("API_URL", "ONLY_GLOBAL"), store.visible(SecretStore.chain("C:/Work/Terrio", null)).map { it.name })
    }

    @Test
    fun `metadata is readable without the key, and a value can be neither read nor moved without it`() {
        store.set("TOKEN_A", SecretScope.GLOBAL, "value-of-token-a", source = "C:/x/.env")
        val locked = SecretStore(file, object : KeyProtector {
            override val name = "passphrase"
            override fun wrap(key: ByteArray) = error("no")
            override fun unwrap(blob: String): ByteArray = error("this user cannot open the key")
        })
        val meta = locked.list().single()
        assertEquals("TOKEN_A", meta.name)
        assertEquals("C:/x/.env", meta.source)
        assertEquals(1, locked.visible(SecretStore.chain()).size)
        assertFailsWith<IllegalStateException> { locked.resolve(SecretStore.chain()) }
        // A ciphertext is bound to its name and scope: renaming it in the file makes it unreadable.
        Files.writeString(file, Files.readString(file).replace("\"name\":\"TOKEN_A\"", "\"name\":\"TOKEN_B\""))
        assertFailsWith<java.security.GeneralSecurityException> { SecretStore(file, protector).resolve(SecretStore.chain()) }
    }

    @Test
    fun `rotation keeps the creation time, use is recorded without a value, remove forgets`() {
        store.set("DB_PASSWORD", SecretScope.GLOBAL, "first-password")
        later()
        val rotated = store.set("DB_PASSWORD", SecretScope.GLOBAL, "second-password")
        assertEquals("2026-10-08T12:00:00Z", rotated.created.replace(".000", ""))
        assertTrue(rotated.rotated != null && rotated.rotated != rotated.created)
        assertNull(store.list().single().lastUsed)
        later()
        assertEquals("second-password", store.resolve(SecretStore.chain(), usedBy = "env run: docker").getValue("DB_PASSWORD").value)
        later()
        store.resolve(SecretStore.chain(), usedBy = "youtrack-mcp")
        val used = store.list().single()
        assertEquals(listOf("youtrack-mcp", "env run: docker"), used.usedBy)
        assertTrue(used.lastUsed != null)
        assertTrue(store.remove("DB_PASSWORD", SecretScope.GLOBAL))
        assertFalse(store.remove("DB_PASSWORD", SecretScope.GLOBAL))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `names, values and the passphrase are checked, and a wrong passphrase says nothing about the contents`() {
        assertFailsWith<IllegalArgumentException> { store.set("1BAD", SecretScope.GLOBAL, "x") }
        assertFailsWith<IllegalArgumentException> { store.set("HAS SPACE", SecretScope.GLOBAL, "x") }
        assertFailsWith<IllegalArgumentException> { store.set("EMPTY", SecretScope.GLOBAL, "") }
        assertFailsWith<IllegalArgumentException> { SecretScope.parse("team:x") }
        assertEquals(SecretScope.repository("a/b"), SecretScope.parse("repository:A\\B"))
        store.set("TOKEN", SecretScope.GLOBAL, "super-secret-token-value")
        val wrong = SecretStore(file, PassphraseProtector("wrong".toCharArray(), iterations = 1_000))
        val failure = assertFailsWith<IllegalStateException> { wrong.resolve(SecretStore.chain()) }
        assertEquals("wrong passphrase for this vault", failure.message)
    }

    @Test
    fun `the known values are cached until the file changes, and every stored value is among them`() {
        store.set("ONE", SecretScope.GLOBAL, "value-one-123")
        store.set("TWO", SecretScope.repository("r"), "value-two-456")
        assertEquals(setOf("value-one-123", "value-two-456"), store.knownValues().toSet())
        store.set("THREE", SecretScope.GLOBAL, "value-three-789")
        assertEquals(3, store.knownValues().size)
    }
}
