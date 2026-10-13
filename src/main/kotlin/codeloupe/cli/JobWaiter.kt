package codeloupe.cli

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Blocks until a job's chain ends, for as long as that takes: long polls of up to five minutes each, so no connection
 * stays open for hours, and a daemon that is restarting — gone, starting, or still ending and not answering — is asked
 * again every [retryMs] for up to [giveUpMs]. A restarted daemon queues the job again, so the wait ends with its real result.
 * Costs no CPU meanwhile.
 */
class JobWaiter(
    private val poll: (path: String) -> Pair<Int, JsonObject>,
    private val retryMs: Long = RETRY_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
) {
    constructor(client: DaemonClient) : this({ path -> client.send("GET", path, timeout = null) })

    /** The exit code to mirror and the compact report. */
    fun wait(id: String): Pair<Int, String> {
        var failingSince: Long? = null
        while (true) {
            val (status, body) = try {
                poll("/jobs/$id/wait?timeoutSec=$POLL_SEC").also { failingSince = null }
            } catch (e: Exception) {
                // IOException: no daemon on the port; IllegalStateException: one that is starting or ending and does not answer yet.
                if (e !is IOException && e !is IllegalStateException) throw e
                val since = failingSince ?: System.nanoTime().also { failingSince = it }
                if ((System.nanoTime() - since) / 1_000_000 >= giveUpMs) throw IllegalStateException("lost the daemon while waiting for $id: ${e.message}")
                Thread.sleep(retryMs)
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
        const val RETRY_MS = 1_000L
        const val GIVE_UP_MS = 120_000L
    }
}
