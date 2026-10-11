package codeloupe.brain

import codeloupe.jobs.Executables
import codeloupe.platform.NativeCalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs a workspace's `brain/brain.mjs` with node and returns its standard output. The program and its arguments are an
 * array (never a shell line), the working directory is the workspace root, and the run ends after [timeoutMs]. The
 * brain's file format stays the CLI's business: this class never reads brain files.
 */
class BrainCli(private val timeoutMs: Long = TIMEOUT_MS, private val node: String = "node") : BrainEngine {
    override suspend fun run(root: Path, args: List<String>, env: Map<String, String>): String = withContext(Dispatchers.IO) {
        val program = Executables.resolve(node, root, System.getenv(), NativeCalls.isWindows)
        val process = try {
            ProcessBuilder(listOf(program, SCRIPT) + args).directory(root.toFile()).apply { environment()["NO_COLOR"] = "1"; environment().putAll(env) }.start()
        } catch (e: IOException) {
            throw BrainException(BrainException.Kind.NODE_MISSING, "node could not be started (${e.message.orEmpty().lineSequence().first()}); install Node.js or add it to the daemon's PATH")
        }
        process.outputStream.close()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val readers = listOf(
            thread(isDaemon = true) { copy(process.inputStream, out, MAX_OUTPUT) },
            thread(isDaemon = true) { copy(process.errorStream, err, MAX_ERROR) },
        )
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw BrainException(BrainException.Kind.TIMEOUT, "brain.mjs did not answer within ${timeoutMs / 1000} s")
        }
        readers.forEach { it.join(1_000) }
        if (process.exitValue() != 0) {
            val reason = err.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "exit ${process.exitValue()}"
            throw BrainException(BrainException.Kind.FAILED, "brain.mjs ${args.firstOrNull().orEmpty()} failed: ${reason.take(300)}")
        }
        out.toString(Charsets.UTF_8)
    }

    // Reads on to the end past the cap so the child never blocks on a full pipe.
    private fun copy(from: java.io.InputStream, to: ByteArrayOutputStream, max: Int) {
        val buffer = ByteArray(8192)
        runCatching {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                if (to.size() < max) to.write(buffer, 0, minOf(n, max - to.size()))
            }
        }
    }

    companion object {
        const val SCRIPT = "brain/brain.mjs"
        const val TIMEOUT_MS = 20_000L
        private const val MAX_OUTPUT = 8 * 1024 * 1024
        private const val MAX_ERROR = 16 * 1024
    }
}
