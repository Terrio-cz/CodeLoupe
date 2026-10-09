package codeloupe.secrets

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VaultLocationTest {
    private val user = Path.of("/home/me")

    @Test
    fun `a vault under the cache folder gets a warning`() {
        val linux = VaultLocation.cacheWarning(Path.of("/home/me/.cache/codeloupe/secrets/vault.env"), emptyMap(), "Linux", user)
        assertContains(assertNotNull(linux), "cache folder")
        assertContains(assertNotNull(VaultLocation.cacheWarning(Path.of("/Users/me/Library/Caches/codeloupe/secrets/vault.env"), emptyMap(), "Mac OS X", Path.of("/Users/me"))), "CODELOUPE_HOME")
        assertNotNull(VaultLocation.cacheWarning(Path.of("/data/xdg/codeloupe/secrets/vault.env"), mapOf("XDG_CACHE_HOME" to "/data/xdg"), "Linux", user))
    }

    @Test
    fun `a vault elsewhere, and any vault on Windows, gets none`() {
        assertNull(VaultLocation.cacheWarning(Path.of("/home/me/.local/share/codeloupe/secrets/vault.env"), emptyMap(), "Linux", user))
        assertNull(VaultLocation.cacheWarning(Path.of("/home/me/.cache/../keep/secrets/vault.env"), emptyMap(), "Linux", user))
        assertNull(VaultLocation.cacheWarning(Path.of("/home/me/.cache/codeloupe/secrets/vault.env"), emptyMap(), "Windows 11", user))
    }
}
