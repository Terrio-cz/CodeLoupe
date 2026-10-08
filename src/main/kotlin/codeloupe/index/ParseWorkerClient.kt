package codeloupe.index

import codeloupe.JsonFormat
import codeloupe.lang.FileFacts
import codeloupe.platform.JavaProcess
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The daemon's side of a [ParseWorker]: starts it on the first file to parse, asks it for the facts of each file, and starts
 * it again when it ended (idle, or a crash). A worker that does not answer within [answerSeconds] is killed. [extract] gives
 * null when no worker can be had, so the caller parses itself rather than failing.
 */
class ParseWorkerClient(
    private val idleSeconds: Long = ParseWorker.DEFAULT_IDLE_SECONDS,
    private val answerSeconds: Long = 120,
    private val command: () -> List<String> = { JavaProcess.command(ParseWorker::class.java.name, ParseWorker.JVM_ARGS, listOf(idleSeconds.toString())) },
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private var process: Process? = null
    private var input: BufferedReader? = null
    private var output: BufferedWriter? = null
    private var next = 0L
    private val killer = Executors.newSingleThreadScheduledExecutor { Thread(it, "codeloupe-parse-watch").apply { isDaemon = true } }

    /** The process id of the running worker, or null when none runs. */
    @get:Synchronized
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    @Synchronized
    fun extract(path: String, text: String): FileFacts? {
        repeat(ATTEMPTS) { attempt ->
            try {
                return roundTrip(path, text)
            } catch (e: IOException) {
                log("parse worker: ${e.message.orEmpty().lineSequence().first()}" + if (attempt + 1 < ATTEMPTS) "; starting another" else "")
                stop()
            }
        }
        return null
    }

    @Synchronized
    override fun close() {
        stop()
        killer.shutdownNow()
    }

    private fun roundTrip(path: String, text: String): FileFacts {
        val worker = ensure()
        val id = next++
        val watch = killer.schedule({ worker.destroyForcibly() }, answerSeconds, TimeUnit.SECONDS)
        try {
            output!!.write(JsonFormat.json.encodeToString(ParseRequest.serializer(), ParseRequest(id, path, text)))
            output!!.newLine()
            output!!.flush()
            // A JVM message that slipped onto stdout is not an answer.
            var line = input!!.readLine() ?: throw IOException("ended before answering for $path")
            while (!line.startsWith("{")) line = input!!.readLine() ?: throw IOException("ended before answering for $path")
            val reply = JsonFormat.json.decodeFromString(ParseReply.serializer(), line)
            if (reply.id != id) throw IOException("answered ${reply.id} to $id")
            return reply.facts
        } finally {
            watch.cancel(false)
        }
    }

    private fun ensure(): Process {
        process?.takeIf { it.isAlive }?.let { return it }
        stop()
        val started = ProcessBuilder(command()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = started
        input = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8), BUFFER)
        output = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8), BUFFER)
        return started
    }

    private fun stop() {
        process?.destroyForcibly()
        runCatching { output?.close() }
        process = null
        input = null
        output = null
    }

    private companion object {
        const val ATTEMPTS = 2
        const val BUFFER = 1 shl 16
    }
}
