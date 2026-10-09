package codeloupe.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScrubberTest {
    @Test
    fun `provider tokens and command line credentials are masked`() {
        val stripe = "sk_" + "live_4eC39HqLyjWDarjtT1zdp7dc"
        val npm = "npm_" + "abcdefghijklmnopqrstuvwxyz0123456789"
        val google = "AIza" + "SyA-abcdefghijklmnopqrstuvwxyz012345"
        listOf(
            "pay with $stripe now",
            "//registry:_authToken=$npm",
            "key=$google",
            "curl -u admin:hunter2hunter2 https://x.example",
            "mysql -u root -pS3cretPass db",
            "PRIVATE_KEY=abc123def456",
            "CODELOUPE_PASSPHRASE=correct-horse",
        ).forEach { line ->
            val out = Scrubber.text(line)
            assertFalse(listOf(stripe, npm, google, "hunter2hunter2", "S3cretPass", "abc123def456", "correct-horse").any { it in out }, "$line -> $out")
        }
    }

    @Test
    fun `ordinary text and user ids stay as they are`() {
        assertEquals("docker exec -u root:root app ls", Scrubber.text("docker exec -u root:root app ls"))
        assertEquals("mysqldump is not run here", Scrubber.text("mysqldump is not run here"))
    }
}
