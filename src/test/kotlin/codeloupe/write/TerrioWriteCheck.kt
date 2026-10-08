package codeloupe.write

import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.config.WriteConfig
import codeloupe.daemon.JobQueue
import codeloupe.repo.Registry
import codeloupe.query.SymbolQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renames in a scratch clone of a real repository, then the repository's own build: set CODELOUPE_WRITE_CLONE to the repository to
 * clone (read only; the clone lives under the temp directory) and CODELOUPE_WRITE_SYMBOLS to a file of symbol names, one per line.
 * Ten renames must be made and `./gradlew compileKotlin compileTestKotlin` must pass. The report goes to CODELOUPE_WRITE_REPORT.
 */
class TerrioWriteCheck {
    @Test
    fun `ten renames in a scratch clone and the repository's own compile`() {
        val source = System.getenv("CODELOUPE_WRITE_CLONE")
        assumeTrue(source != null && System.getenv("CODELOUPE_WRITE_SYMBOLS") != null, "CODELOUPE_WRITE_CLONE is not set")
        val scratch = Files.createTempDirectory("codeloupe-write-clone")
        val repo = scratch.resolve("repo")
        TestRepos.git(scratch, "clone", "-q", "--local", source!!, repo.toString())
        val symbols = Files.readAllLines(Path.of(System.getenv("CODELOUPE_WRITE_SYMBOLS"))).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val config = Config(TestRepos.tmpDir("write-home"), 0, 120_000, buildTimeoutMs = 600_000, buildHeapMb = 1024, defaultRoot = null, overlayCheckMs = 0)
        val registry = Registry(config, JobQueue(CoroutineScope(Dispatchers.Default)))
        val service = WriteService(registry, WritePolicy(WriteConfig()), WriteJournal(config.home.resolve("writes.jsonl")))
        val report = StringBuilder("clone of $source at ${TestRepos.git(repo, "rev-parse", "HEAD")}\n")
        var renamed = 0
        for (name in symbols) {
            if (renamed >= TARGET) break
            val to = name.substringBefore('(').substringAfterLast('.') + "Renamed"
            val hash = runBlocking {
                registry.query(repo.toString()) { view -> SymbolQuery.run(view, SymbolQuery.Args(name)) }
            }.lineSequence().first().let { Regex("hash=([0-9a-f]{10})").find(it)?.groupValues?.get(1) }
            if (hash == null) {
                report.appendLine("skip $name: symbol shows no single declaration")
                continue
            }
            try {
                val answer = runBlocking { service.rename(repo.toString(), name, to, hash, false) }
                renamed++
                report.appendLine("renamed $name -> $to: ${answer.lineSequence().first()}")
                if ("left to you" in answer) answer.lineSequence().drop(1).take(8).forEach { report.appendLine("    $it") }
            } catch (e: WriteRefused) {
                report.appendLine("refused $name: ${e.message?.lineSequence()?.first()}")
            }
        }
        report.appendLine("$renamed renames made")
        val gradle = ProcessBuilder(
            if (System.getProperty("os.name").lowercase().startsWith("windows")) mutableListOf("cmd", "/c", repo.resolve("gradlew.bat").toString()) else mutableListOf(repo.resolve("gradlew").toString()),
        ).apply {
            command().addAll(listOf("compileKotlin", "compileTestKotlin", "--console=plain", "--no-daemon", "-q"))
            directory(repo.toFile())
            environment()["JAVA_HOME"] = System.getProperty("java.home")
            redirectErrorStream(true)
        }
        val process = gradle.start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        report.appendLine("compile exit $exit")
        if (exit != 0) report.appendLine(output.lines().filter { it.isNotBlank() && !it.startsWith("WARNING") }.take(40).joinToString("\n"))
        System.getenv("CODELOUPE_WRITE_REPORT")?.let { Files.writeString(Path.of(it), report.toString()) }
        assertTrue(renamed >= TARGET, report.toString())
        assertEquals(0, exit, report.toString())
    }

    private companion object {
        val TARGET = System.getenv("CODELOUPE_WRITE_TARGET")?.toIntOrNull() ?: 10
    }
}
