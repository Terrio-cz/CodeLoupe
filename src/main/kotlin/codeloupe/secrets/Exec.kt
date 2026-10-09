package codeloupe.secrets

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs an OS key-store tool; a secret goes in on stdin where the tool allows it, never on a command line we log. */
internal object Exec {
    class Result(val exit: Int, val out: String)

    fun run(command: List<String>, stdin: String? = null, timeoutSec: Long = 30): Result {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        stdin?.let { text -> process.outputStream.use { it.write(text.toByteArray()) } } ?: process.outputStream.close()
        // stderr is drained and dropped: it may echo what was asked.
        val err = Thread { process.errorStream.readAllBytes() }.apply { isDaemon = true; start() }
        // A tool waiting for a prompt that nobody sees (a locked keyring) never closes its output: the read is what the deadline bounds.
        val output = CompletableFuture<ByteArray>()
        Thread { output.complete(runCatching { process.inputStream.readAllBytes() }.getOrDefault(ByteArray(0))) }.apply { isDaemon = true; start() }
        val out = try {
            output.get(timeoutSec, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        } catch (e: TimeoutException) {
            process.destroyForcibly()
            throw IllegalStateException("${command.first()} did not answer in ${timeoutSec}s")
        }
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("${command.first()} did not answer in ${timeoutSec}s")
        }
        err.join(1_000)
        return Result(process.exitValue(), out.trim())
    }
}
