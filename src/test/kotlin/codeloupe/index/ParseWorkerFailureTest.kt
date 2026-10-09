package codeloupe.index

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A worker that answers with something that is not a reply. */
object GarblingParseWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        while (readlnOrNull() != null) {
            println("{this is not a reply")
            System.out.flush()
        }
    }
}

class ParseWorkerFailureTest {
    private fun command(): List<String> =
        listOf(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"), "codeloupe.index.GarblingParseWorker")

    @Test
    fun `a reply that does not decode is a failed worker, not an exception for the caller`() {
        val log = ArrayList<String>()
        ParseWorkerClient(command = ::command, log = { log += it }).use { client ->
            assertNull(client.extract("src/Billing.kt", "package demo\n\nclass Billing\n"))
        }
        assertTrue(log.size >= 2, "both attempts are logged: $log")
    }

    @Test
    fun `a worker whose parent is gone ends even inside a parse, an idle one only after its time`() {
        assertTrue(ParseWorker.shouldEnd(parentAlive = false, working = true, idleForMs = 0, idleMs = 300_000))
        assertTrue(ParseWorker.shouldEnd(parentAlive = true, working = false, idleForMs = 301_000, idleMs = 300_000))
        assertFalse(ParseWorker.shouldEnd(parentAlive = true, working = true, idleForMs = 999_000, idleMs = 300_000), "a long parse is not idle")
        assertFalse(ParseWorker.shouldEnd(parentAlive = true, working = false, idleForMs = 1_000, idleMs = 300_000))
    }
}
