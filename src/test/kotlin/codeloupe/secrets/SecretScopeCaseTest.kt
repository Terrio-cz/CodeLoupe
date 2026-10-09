package codeloupe.secrets

import codeloupe.TestRepos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** CL-172: ids keep their case where the file system does, and an entry an older version stored lower-cased still resolves. */
class SecretScopeCaseTest {
    private val home = TestRepos.tmpDir("vault-case")
    private val file = home.resolve("secrets").resolve("vault.env")
    private val store = SecretStore(file, PassphraseProtector("correct horse".toCharArray(), iterations = 1_000))

    private fun value(chain: List<SecretScope>) = store.resolve(chain).getValue("DB_URL").value

    @Test
    fun `two ids that differ by case are two scopes where the file system is case sensitive`() {
        val upper = SecretScope.repository("/x/Proj", foldCase = false)
        val lower = SecretScope.repository("/x/proj", foldCase = false)
        assertNotEquals(upper, lower)
        assertEquals("repo:/x/Proj", upper.toString())
        store.set("DB_URL", upper, "value-for-upper")
        store.set("DB_URL", lower, "value-for-lower")
        assertEquals("value-for-upper", value(SecretStore.chain(repository = "/x/Proj", foldCase = false)))
        assertEquals("value-for-lower", value(SecretStore.chain(repository = "/x/proj", foldCase = false)))
        assertEquals(2, store.list().size)
    }

    @Test
    fun `where the file system folds case the ids are lower-cased as before`() {
        assertEquals(SecretScope.repository("/x/proj", foldCase = true), SecretScope.repository("/X\\Proj/", foldCase = true))
        assertEquals("workspace:c:/work/terrio", SecretScope.workspace("C:\\Work\\Terrio", foldCase = true).toString())
        assertTrue(SecretScope.foldsCase("Windows 11"))
        assertTrue(SecretScope.foldsCase("Mac OS X"))
        assertFalse(SecretScope.foldsCase("Linux"))
    }

    @Test
    fun `a vault written by the previous version still resolves for a caller with capitals, and the exact id wins`() {
        // What the previous version wrote for /home/me/MyRepo.
        val legacy = SecretScope.repository("/home/me/MyRepo", foldCase = true)
        store.set("DB_URL", legacy, "from-the-old-vault")
        val caller = SecretStore.chain(repository = "/home/me/MyRepo", foldCase = false)
        assertEquals("from-the-old-vault", value(caller))
        assertEquals(listOf("DB_URL"), store.visible(caller).map { it.name })
        assertTrue(store.holds("DB_URL", SecretScope.repository("/home/me/MyRepo", foldCase = false), "from-the-old-vault"))

        store.set("DB_URL", SecretScope.repository("/home/me/MyRepo", foldCase = false), "written-by-the-new-version")
        assertEquals("written-by-the-new-version", value(caller), "the entry under the exact id beats the lower-cased one")
        assertEquals("from-the-old-vault", value(SecretStore.chain(repository = "/home/me/myrepo", foldCase = false)), "the other spelling still sees only the old entry")

        assertTrue(store.remove("DB_URL", SecretScope.repository("/home/me/MyRepo", foldCase = false)))
        assertEquals("from-the-old-vault", value(caller), "removing the exact entry uncovers the legacy one")
        assertTrue(store.remove("DB_URL", SecretScope.repository("/home/me/MyRepo", foldCase = false)), "a name only the legacy entry holds is removed through the exact id")
        assertTrue(store.list().isEmpty())
    }
}
