package codeloupe.platform

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The standard output of an OS tool (`ps`, `lsof`, `netstat`), read under a deadline. Waiting for the exit after reading to the end
 * does not bound anything: a tool that hangs on a stale network mount never closes its output, so the read is what must give up.
 */
object ToolOutput {
    /** The output of [command], or an empty string when it cannot be started or does not finish within [timeoutSeconds] (it is killed then). */
    fun read(timeoutSeconds: Long, vararg command: String, mergeErrors: Boolean = false): String {
        val builder = ProcessBuilder(*command).redirectErrorStream(mergeErrors)
        if (!mergeErrors) builder.redirectError(ProcessBuilder.Redirect.DISCARD)
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ""
        }
        process.outputStream.close()
        val bytes = CompletableFuture<ByteArray>()
        Thread({ bytes.complete(runCatching { process.inputStream.readAllBytes() }.getOrDefault(ByteArray(0))) }, "tool-output").apply { isDaemon = true }.start()
        return try {
            String(bytes.get(timeoutSeconds, TimeUnit.SECONDS), Charsets.UTF_8).also { if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly() }
        } catch (e: TimeoutException) {
            process.destroyForcibly()
            ""
        }
    }
}
