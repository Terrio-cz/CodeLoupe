package codeloupe.index

import codeloupe.lang.FileFacts
import codeloupe.lang.Languages
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the language extractors on one dedicated thread, or in a [ParseWorkerClient]'s process once [useWorker] was called (the
 * daemon does, to keep the compiler's parser out of its own memory). A file that cannot be read gets no facts; it never fails the caller.
 */
object Extraction {
    // Syntax trees are walked recursively; a deep expression (thousands of `+` terms) needs far more stack
    // than a default thread has. The stack is reserved, not committed, so it costs no memory until used.
    private const val STACK_BYTES = 256L * 1024 * 1024

    private val extractor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(null, it, "codeloupe-extract", STACK_BYTES).apply { isDaemon = true } }
    }

    /** Files parsed in this process: what reusing facts by content saves. */
    val parsed = AtomicLong()

    @Volatile
    private var worker: ParseWorkerClient? = null

    /** From now on the files are parsed by [client]'s process; this process parses only when that cannot be had. */
    fun useWorker(client: ParseWorkerClient) {
        worker = client
    }

    /** Back to parsing in this process; ends the worker. */
    fun useThisProcess() {
        worker?.close()
        worker = null
    }

    fun extract(path: String, text: String): FileFacts {
        parsed.incrementAndGet()
        return worker?.extract(path, text) ?: parseHere(path, text)
    }

    /** Parses in this process, whatever [useWorker] says: what the worker itself does. */
    fun parseHere(path: String, text: String): FileFacts = try {
        extractor.submit<FileFacts> { Languages.extract(path, text)!! }.get()
    } catch (e: ExecutionException) {
        System.err.println("codeloupe: $path indexed without facts: ${e.cause ?: e}")
        FileFacts("", emptyList(), emptyList(), emptyList(), errors = 1)
    }
}
