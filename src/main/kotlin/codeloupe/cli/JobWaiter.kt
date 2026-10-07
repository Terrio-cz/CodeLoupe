package codeloupe.cli

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Blocks until a job's chain ends, for as long as that takes: long polls of up to five minutes each, so no connection
 * stays open for hours and a restarted daemon (which reports the job lost) is reached again. Costs no CPU meanwhile.
 */
class JobWaiter(private val client: DaemonClient) {
    /** The exit code to mirror and the compact report. */
    fun wait(id: String): Pair<Int, String> {
        var failures = 0
        while (true) {
            val (status, body) = try {
                client.send("GET", "/jobs/$id/wait?timeoutSec=$POLL_SEC", timeout = null).also { failures = 0 }
            } catch (e: IOException) {
                if (++failures >= MAX_FAILURES) throw IllegalStateException("lost the daemon while waiting for $id: ${e.message}")
                continue
            }
            if (status != 200) return 1 to (body["error"]?.jsonPrimitive?.content ?: "HTTP $status")
            if (body["done"]?.jsonPrimitive?.content == "true") {
                return body.getValue("exit").jsonPrimitive.int to body.getValue("text").jsonPrimitive.content
            }
        }
    }

    private companion object {
        const val POLL_SEC = 300
        const val MAX_FAILURES = 3
    }
}
