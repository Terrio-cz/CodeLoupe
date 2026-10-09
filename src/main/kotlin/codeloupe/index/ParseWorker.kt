package codeloupe.index

import codeloupe.JsonFormat
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess

/**
 * Child process of the daemon that parses files for it: one [ParseRequest] JSON line on stdin, one [ParseReply] line on
 * stdout. The Kotlin compiler's parser is some 30 MB of classes, symbols and code the daemon would hold for as long as it
 * lives; here it is held only while files are being edited. The worker ends when its parent closes the pipe, or when
 * nothing was asked for the number of seconds given as its argument.
 */
object ParseWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        val idleMs = (args.firstOrNull()?.toLongOrNull() ?: DEFAULT_IDLE_SECONDS) * 1000
        // Anything the parser's libraries print must not be taken for an answer.
        val replies = PrintStream(FileOutputStream(FileDescriptor.out), false, Charsets.UTF_8)
        System.setOut(System.err)
        val lastActive = AtomicLong(System.currentTimeMillis())
        val working = java.util.concurrent.atomic.AtomicBoolean(false)
        val parent = ProcessHandle.current().parent().orElse(null)
        Thread {
            while (true) {
                Thread.sleep(WATCH_MS)
                if (shouldEnd(parent?.isAlive != false, working.get(), System.currentTimeMillis() - lastActive.get(), idleMs)) exitProcess(0)
            }
        }.apply { isDaemon = true }.start()
        val requests = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8), BUFFER)
        while (true) {
            val line = requests.readLine() ?: exitProcess(0)
            working.set(true)
            val request = JsonFormat.json.decodeFromString(ParseRequest.serializer(), line)
            val facts = Extraction.parseHere(request.path, request.text)
            replies.println(JsonFormat.json.encodeToString(ParseReply.serializer(), ParseReply(request.id, facts)))
            replies.flush()
            lastActive.set(System.currentTimeMillis())
            working.set(false)
        }
    }

    /**
     * Whether the worker ends: its parent is gone (a daemon that crashed while the worker was inside a parse, which never reads the
     * closed pipe), or nothing was asked for [idleMs] and no parse is under way.
     */
    internal fun shouldEnd(parentAlive: Boolean, working: Boolean, idleForMs: Long, idleMs: Long): Boolean = !parentAlive || (!working && idleForMs > idleMs)

    /** The JVM flags the daemon starts a worker with: a small heap, the compiler's parser needs no more for a file of 512 KB. */
    val JVM_ARGS = listOf(
        "-Xlog:disable", "-Xms16m", "-Xmx96m", "-Xmn10m", "-Xss512k", "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30",
        "-XX:+UseCompactObjectHeaders", "-XX:TieredStopAtLevel=1", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxMetaspaceSize=128m",
    )

    const val DEFAULT_IDLE_SECONDS = 300L
    private const val WATCH_MS = 1_000L
    private const val BUFFER = 1 shl 20
}
