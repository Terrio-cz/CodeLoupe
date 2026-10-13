package codeloupe.jobs

import codeloupe.TestRepos
import codeloupe.secrets.KeyProtector
import codeloupe.secrets.PassphraseProtector
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JobSpecKeyTest {
    private val spec = JobSpec(listOf("./gradlew", "test", "--token=s3cret-value"), mapOf("API_TOKEN" to "s3cret-env"), listOf("notify:green"), listOf("notify:red"))

    @Test
    fun `a sealed launch opens again with the same key file, under its own job id only`() {
        val home = TestRepos.tmpDir("spec-key")
        val protector = PassphraseProtector("test".toCharArray(), iterations = 1_000)
        val sealed = assertNotNull(JobSpecKey(home.resolve("jobs.key")) { protector }.seal("J1", spec))
        assertFalse("s3cret" in sealed, "nothing readable in the stored text")
        assertFalse("s3cret" in Files.readString(home.resolve("jobs.key")))
        val again = JobSpecKey(home.resolve("jobs.key")) { protector }
        assertEquals(spec, again.open("J1", sealed), "a new daemon opens it")
        assertNull(again.open("J2", sealed), "moved under another id it does not open")
        assertNull(again.open("J1", "garbage"))
    }

    @Test
    fun `without a key store nothing is sealed and the reason is kept`() {
        val home = TestRepos.tmpDir("spec-key-none")
        val key = JobSpecKey(home.resolve("jobs.key")) {
            object : KeyProtector {
                override val name = "none"

                override fun wrap(key: ByteArray): String = error("no OS key store here")

                override fun unwrap(blob: String): ByteArray = error("no OS key store here")
            }
        }
        assertNull(key.seal("J1", spec))
        assertEquals("no OS key store here", key.problem)
        assertFalse(Files.exists(home.resolve("jobs.key")))
    }
}
