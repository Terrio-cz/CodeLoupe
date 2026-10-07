package codeloupe.repo

import codeloupe.JsonFormat
import codeloupe.index.BuildResult
import codeloupe.index.BuildWorker
import codeloupe.index.StoreUpdate
import codeloupe.platform.JavaProcess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Runs heavy parsing in a child JVM, so the parser's memory for many files goes away with the process. */
open class BuildLauncher(private val heapMb: Int, private val timeoutMs: Long) {
    /** A full base build of [commit] into [outFile]. */
    open fun build(commonDir: String, commit: String, outFile: Path, workDir: Path): BuildResult =
        run(listOf(commonDir, commit, outFile.toString()), workDir)

    /** Applies [update] to [dbFile]: a base copy becoming [commit]'s base, or a worktree overlay when [commit] is null. */
    open fun update(commonDir: String, commit: String?, dbFile: Path, update: StoreUpdate, workDir: Path): BuildResult {
        val file = Files.createTempFile(workDir, "update-", ".json")
        try {
            Files.writeString(file, JsonFormat.json.encodeToString(StoreUpdate.serializer(), update))
            return run(listOf(BuildWorker.UPDATE, commonDir, commit ?: BuildWorker.NO_COMMIT, dbFile.toString(), file.toString()), workDir)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun run(args: List<String>, workDir: Path): BuildResult {
        val command = JavaProcess.command(BuildWorker::class.java.name, jvmArgs(), args)
        val process = ProcessBuilder(command).directory(workDir.toFile()).start().apply { outputStream.close() }
        val stderr = CompletableFuture.supplyAsync { tail(process.errorStream.readAllBytes().toString(Charsets.UTF_8)) }
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly().waitFor()
            throw IllegalStateException("build timed out after $timeoutMs ms")
        }
        val code = process.exitValue()
        val result = stdout.join().trim().lines().lastOrNull()
            ?.let { runCatching { JsonFormat.json.decodeFromString(BuildResult.serializer(), it) }.getOrNull() }
        if (code != 0 || result?.ok != true) {
            // Skip the JVM's own WARNING lines; the reason is the last other line.
            val reason = stderr.join().lines().lastOrNull { it.isNotBlank() && !it.startsWith("WARNING") }.orEmpty()
            throw IllegalStateException(result?.error ?: "build exited $code: $reason")
        }
        return result
    }

    // The parser needs heap only for one file at a time; SerialGC keeps the peak close to what is live.
    private fun jvmArgs() = listOf("-Xmx${heapMb}m", "-XX:+UseSerialGC", "-Xshare:auto")

    private fun tail(text: String) = if (text.length > 4000) text.takeLast(4000) else text
}
