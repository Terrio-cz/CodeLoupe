package codeloupe.secrets

import codeloupe.TestRepos
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The daemon reads (and notes the use), the CLI writes: neither may lose the other's change. */
class SecretStoreConcurrencyTest {
    private val file = TestRepos.tmpDir("vault-race").resolve("secrets").resolve("vault.env")
    private fun store() = SecretStore(file, PassphraseProtector("pw".toCharArray(), iterations = 1_000))

    @Test
    fun `writers and readers on separate store instances keep every entry`() {
        store().set("SEED", SecretScope.GLOBAL, "seed-fake-value")
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        val writers = (0 until 4).map { w ->
            pool.submit {
                start.await()
                val own = store()
                repeat(12) { i -> own.set("W${w}_K$i", SecretScope.GLOBAL, "fake-value-$w-$i") }
            }
        }
        val readers = (0 until 2).map {
            pool.submit {
                start.await()
                val own = store()
                repeat(30) { own.resolve(SecretStore.chain(), usedBy = "reader-$it", names = setOf("SEED")) }
            }
        }
        start.countDown()
        (writers + readers).forEach { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        val names = store().list().map { it.name }.toSet()
        assertEquals(4 * 12 + 1, names.size, "no entry was lost: ${names.size}")
        assertTrue(store().list().single { it.name == "SEED" }.lastUsed != null)
        assertTrue(store().holds("W3_K11", SecretScope.GLOBAL, "fake-value-3-11"))
    }
}
