package codeloupe.index

import codeloupe.lang.FileFacts
import codeloupe.lang.Languages
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the language extractors on dedicated threads, or in a [ParseWorkerClient]'s process once [useWorker] was called (the
 * daemon does, to keep the compiler's parser out of its own memory). One thread unless [useThreads] says more: the worker
 * processes do, the daemon does not. A file that cannot be read gets no facts; it never fails the caller.
 */
object Extraction {
    // Syntax trees are walked recursively; a deep expression (thousands of `+` terms) needs far more stack
    // than a default thread has. The stack is reserved, not committed, so it costs no memory until used.
    private const val STACK_BYTES = 256L * 1024 * 1024

    private val extractors: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { Thread(null, it, "codeloupe-extract", STACK_BYTES).apply { isDaemon = true } }
    }

    /** Files parsed in this process: what reusing facts by content saves. */
    val parsed = AtomicLong()

    @Volatile
    private var worker: ParseWorkerClient? = null

    /** Whether files go to a worker process rather than being parsed in this one. */
    val usesWorker: Boolean get() = worker != null

    /** From now on the files are parsed by [client]'s process; this process parses only when that cannot be had. */
    fun useWorker(client: ParseWorkerClient) {
        worker = client
    }

    /** Back to parsing in this process; ends the worker. */
    fun useThisProcess() {
        worker?.close()
        worker = null
    }

    /**
     * Parses up to [count] files of a batch at once in this process. The parser's memory grows with the text being parsed at
     * once, not with the threads: a [ParseQueue] batch holds as much text as one large file.
     */
    fun useThreads(count: Int) {
        val pool = extractors
        if (count > pool.maximumPoolSize) {
            pool.maximumPoolSize = count
            pool.corePoolSize = count
        } else {
            pool.corePoolSize = count
            pool.maximumPoolSize = count
        }
    }

    /** The threads a worker process parses on: half the cores, at most [MAX_THREADS]. */
    fun workerThreads(cores: Int = Runtime.getRuntime().availableProcessors()): Int = (cores / 2).coerceIn(1, MAX_THREADS)

    fun extract(path: String, text: String): FileFacts = extractAll(listOf(ParseFile(path, text))).single()

    /** The facts of [files], in their order; a worker parses them together. */
    fun extractAll(files: List<ParseFile>): List<FileFacts> {
        if (files.isEmpty()) return emptyList()
        parsed.addAndGet(files.size.toLong())
        return worker?.extractAll(files) ?: parseAllHere(files)
    }

    /** Parses in this process, whatever [useWorker] says: what the worker itself does. */
    fun parseHere(path: String, text: String): FileFacts = parseAllHere(listOf(ParseFile(path, text))).single()

    /** As [parseHere] for several files, on the threads [useThreads] allows; the facts do not depend on how many. */
    fun parseAllHere(files: List<ParseFile>): List<FileFacts> {
        val futures: List<Future<FileFacts>> = files.map { file -> extractors.submit<FileFacts> { Languages.extract(file.path, file.text)!! } }
        return futures.zip(files) { future, file ->
            try {
                future.get()
            } catch (e: ExecutionException) {
                System.err.println("codeloupe: ${file.path} indexed without facts: ${e.cause ?: e}")
                FileFacts("", emptyList(), emptyList(), emptyList(), errors = 1)
            }
        }
    }

    private const val MAX_THREADS = 4
}
