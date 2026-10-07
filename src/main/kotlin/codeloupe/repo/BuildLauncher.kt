package codeloupe.repo

import codeloupe.JsonFormat
import codeloupe.index.BuildResult
import codeloupe.index.BuildWorker
import codeloupe.platform.JavaProcess
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Runs one base build in a child JVM, so the parser's memory goes away with the process. */
class BuildLauncher(private val heapMb: Int, private val timeoutMs: Long) {
    fun build(commonDir: String, commit: String, outFile: Path): BuildResult {
        val command = JavaProcess.command(BuildWorker::class.java.name, jvmArgs(), listOf(commonDir, commit, outFile.toString()))
        val process = ProcessBuilder(command).start().apply { outputStream.close() }
        val stderr = CompletableFuture.supplyAsync { tail(process.errorStream.readAllBytes().toString(Charsets.UTF_8)) }
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly().waitFor()
        val code = process.exitValue()
        val result = stdout.join().trim().lines().lastOrNull()
            ?.let { runCatching { JsonFormat.json.decodeFromString(BuildResult.serializer(), it) }.getOrNull() }
        if (code != 0 || result?.ok != true) {
            throw IllegalStateException(result?.error ?: "build exited $code: ${stderr.join().trim().lines().lastOrNull().orEmpty()}")
        }
        return result
    }

    // The parser needs heap only for one file at a time; SerialGC keeps the peak close to what is live.
    private fun jvmArgs() = listOf("-Xmx${heapMb}m", "-XX:+UseSerialGC", "-Xshare:auto")

    private fun tail(text: String) = if (text.length > 4000) text.takeLast(4000) else text
}
