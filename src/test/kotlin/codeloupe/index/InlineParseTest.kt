package codeloupe.index

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InlineParseTest {
    private fun puts(count: Int, size: Long = 4_000) = (0 until count).map { FilePut("src/F$it.kt", file = "F$it.kt", size = size) }

    @Test
    fun `an update a query waits for stays in the daemon up to a thousand files when a worker parses them, else up to two hundred`() {
        assertFalse(InlineParse.fitsAwaited(puts(300)), "the daemon's own parser: a build worker")
        ParseWorkerClient().use { client ->
            Extraction.useWorker(client)
            try {
                assertTrue(InlineParse.fitsAwaited(puts(300)))
                assertFalse(InlineParse.fitsAwaited(puts(1001)))
                assertFalse(InlineParse.fitsAwaited(puts(3, size = InlineParse.MAX_FILE_BYTES + 1)), "a file too large to read into the daemon")
                assertFalse(InlineParse.fits(puts(300)), "a base sync keeps its limit")
            } finally {
                Extraction.useThisProcess()
            }
        }
    }
}
