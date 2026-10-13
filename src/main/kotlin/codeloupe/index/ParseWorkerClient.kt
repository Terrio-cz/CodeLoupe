package codeloupe.index

import codeloupe.JsonFormat
import codeloupe.lang.FileFacts
import codeloupe.platform.JavaProcess
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The daemon's side of a [ParseWorker]: starts it on the first file to parse, asks it for the facts of a batch of files, and
 * starts it again when it ended (idle, or a crash). A worker that does not answer within [answerSeconds] is killed. [extractAll]
 * gives null when no worker can be had, so the caller parses itself rather than failing. [cacheFlags] gives the worker's AOT
 * cache flags at each start (CL-251), and the log says how long each worker took to start.
 */
class ParseWorkerClient(
    private val idleSeconds: Long = ParseWorker.DEFAULT_IDLE_SECONDS,
    private val answerSeconds: Long = 120,
    private val cacheFlags: () -> List<String> = { emptyList() },
    private val command: () -> List<String> = { JavaProcess.command(ParseWorker::class.java.name, ParseWorker.JVM_ARGS + cacheFlags(), listOf(idleSeconds.toString())) },
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private var process: Process? = null
    private var input: BufferedReader? = null
    private var output: BufferedWriter? = null
    private var next = 0L

    /** When the running worker was started and whether with an AOT cache, until its first answer. */
    private var starting: Pair<Long, Boolean>? = null
    private val killer = ScheduledThreadPoolExecutor(1) { Thread(it, "codeloupe-parse-watch").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }

    /** The process id of the running worker, or null when none runs. */
    @get:Synchronized
    val pid: Long? get() = process?.takeIf { it.isAlive }?.pid()

    fun extract(path: String, text: String): FileFacts? = extractAll(listOf(ParseFile(path, text)))?.single()

    @Synchronized
    fun extractAll(files: List<ParseFile>): List<FileFacts>? {
        repeat(ATTEMPTS) { attempt ->
            try {
                return roundTrip(files)
            } catch (e: Exception) {
                // Also a reply that is not what was asked for (a serializer error): the worker is not trusted after it.
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

    private fun roundTrip(files: List<ParseFile>): List<FileFacts> {
        val worker = ensure()
        val id = next++
        val watch = killer.schedule({ worker.destroyForcibly() }, answerSeconds, TimeUnit.SECONDS)
        val what = files.first().path + if (files.size > 1) " and ${files.size - 1} more" else ""
        try {
            output!!.write(JsonFormat.json.encodeToString(ParseRequest.serializer(), ParseRequest(id, files)))
            output!!.newLine()
            output!!.flush()
            // A JVM message that slipped onto stdout is not an answer.
            var line = input!!.readLine() ?: throw IOException("ended before answering for $what")
            while (!line.startsWith("{")) line = input!!.readLine() ?: throw IOException("ended before answering for $what")
            val reply = JsonFormat.json.decodeFromString(ParseReply.serializer(), line)
            if (reply.id != id) throw IOException("answered ${reply.id} to $id")
            if (reply.facts.size != files.size) throw IOException("answered ${reply.facts.size} files of ${files.size}")
            starting?.let { (at, cached) ->
                starting = null
                val answeredMs = (System.nanoTime() - at) / 1_000_000
                log("parse worker pid ${worker.pid()} started in ${answeredMs - reply.parseMs} ms (${if (cached) "AOT cache" else "no AOT cache"}), first ${files.size} files parsed in ${reply.parseMs} ms")
            }
            return reply.facts
        } finally {
            watch.cancel(false)
        }
    }

    private fun ensure(): Process {
        process?.takeIf { it.isAlive }?.let { return it }
        stop()
        val line = command()
        val at = System.nanoTime()
        val started = ProcessBuilder(line).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        starting = at to line.any { it.startsWith("-XX:AOTCache=") }
        process = started
        input = BufferedReader(InputStreamReader(started.inputStream, Charsets.UTF_8), BUFFER)
        output = BufferedWriter(OutputStreamWriter(started.outputStream, Charsets.UTF_8), BUFFER)
        return started
    }

    private fun stop() {
        process?.destroyForcibly()
        runCatching { output?.close() }
        runCatching { input?.close() }
        process = null
        input = null
        output = null
        starting = null
    }

    private companion object {
        const val ATTEMPTS = 2
        const val BUFFER = 1 shl 16
    }
}
