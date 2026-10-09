package codeloupe.aot

import codeloupe.CodeLoupe
import codeloupe.cli.CliJvm
import codeloupe.cli.DaemonJvm
import codeloupe.cli.LocalHttp
import codeloupe.daemon.DaemonToken
import codeloupe.platform.JavaProcess
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileVisitResult
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * Makes the AOT caches of an install: records a daemon and a CLI call on a throwaway home with a tiny repository (so the
 * daemon that serves is never the one that dumps; a dump takes seconds), has the JVM create both caches from what was
 * recorded, and moves them into place under their final names. Runs in a JVM of its own, started by the first CLI calls
 * after an install ([AotLauncher]). Any failure leaves the install as it was, calls simply run without caches.
 */
internal class AotTrainer(
    private val caches: AotCaches,
    private val classPath: String,
    private val java: String = JavaProcess.executable,
    private val stepSeconds: Long = 120,
) {
    private val id = System.nanoTime().toString(36)
    private val work: Path = caches.suffixed(".work-$id")
    private val stepLog: Path get() = work.resolve("step.log")

    /** True when both caches are in place and marked ready. */
    fun train(): Boolean {
        require(classPath.endsWith(".jar") && File.pathSeparator !in classPath) { "the CLI cache needs a single jar on the class path, not $classPath" }
        removeLeftovers()
        Files.createDirectories(work)
        try {
            val repo = tinyRepo(work.resolve("repo"))
            val cliRecording = work.resolve("cli.aotconf")
            val daemonRecording = work.resolve("daemon.aotconf")
            recordDaemonAndCli(repo, work.resolve("home"), daemonRecording, cliRecording)
            val cliTemp = caches.suffixed(".cli.aot.tmp-$id")
            val daemonTemp = caches.suffixed(".daemon.aot.tmp-$id")
            create(CliJvm.args, listOf("-jar", classPath), cliRecording, cliTemp)
            create(daemonJvm(), listOf("-cp", classPath, MAIN_CLASS), daemonRecording, daemonTemp)
            install(cliTemp, caches.cli)
            install(daemonTemp, caches.daemon)
            // Before the mark: whoever sees the caches ready also sees nothing else of the training left (Windows holds a directory a moment).
            deleteSettled(work)
            val readyTemp = caches.suffixed(".ready.tmp-$id")
            Files.writeString(readyTemp, caches.readyContent())
            Files.move(readyTemp, caches.ready, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            removeOtherBuilds()
            return true
        } finally {
            deleteSettled(work)
        }
    }

    private fun recordDaemonAndCli(repo: Path, home: Path, daemonRecording: Path, cliRecording: Path) {
        Files.createDirectories(home)
        val port = freePort()
        val env = mapOf("CODELOUPE_HOME" to home.toString(), "CODELOUPE_PORT" to port.toString(), TRAINING_ENV to "1")
        val daemonArgs = listOf("daemon", "--detached", "--home", home.toString(), "--port", port.toString())
        val daemon = start(listOf(java) + daemonJvm() + record(daemonRecording) + listOf("-cp", classPath, MAIN_CLASS) + daemonArgs, env, home)
        val http = LocalHttp("http://127.0.0.1:$port")
        try {
            check(waitFor { runCatching { http.request("GET", "/status", readTimeoutMs = 500).status == 200 }.getOrDefault(false) }) { "the training daemon did not start" }
            // The CLI call is the one a user makes first; the second tool brings the daemon's other query classes into the recording.
            val cli = listOf(java) + CliJvm.args + record(cliRecording) + listOf("-jar", classPath, "find", "greet")
            check(run(cli, env, repo) == 0) { "the recorded CLI call failed" }
            val root = repo.toString().replace("\\", "\\\\")
            http.request("POST", "/api/outline", """{"root":"$root","target":"Greeter"}""", authorized(home) + HEADERS, readTimeoutMs = 60_000)
        } finally {
            // The workers the daemon started end with it; they are not waited for, as they would keep the work directory open.
            val workers = daemon.descendants().toList()
            runCatching { http.request("POST", "/shutdown?force=1", headers = authorized(home), readTimeoutMs = 5_000) }
            if (!daemon.waitFor(stepSeconds, TimeUnit.SECONDS)) daemon.destroyForcibly().waitFor()
            workers.forEach { it.destroyForcibly() }
        }
        check(Files.exists(daemonRecording) && Files.exists(cliRecording)) { "the JVM wrote no recording" }
    }

    // The training daemon is the home's own: its token is a file next to it.
    private fun authorized(home: Path): Map<String, String> =
        mapOf(CodeLoupe.HEADER to "1") + listOfNotNull(DaemonToken.read(home)?.let { CodeLoupe.TOKEN_HEADER to it })

    private fun create(jvm: List<String>, launch: List<String>, recording: Path, target: Path) {
        val command = listOf(java) + jvm + listOf("-XX:AOTMode=create", "-XX:AOTConfiguration=$recording", "-XX:AOTCache=$target") + launch
        val code = run(command, emptyMap(), work, stepLog)
        check(code == 0 && Files.exists(target)) { "the JVM did not create ${target.name} (exit $code): ${lastOutput()}" }
    }

    // The JVM prints why it cannot make a cache; the last lines go into the failure note.
    private fun lastOutput(): String =
        runCatching { Files.readAllLines(stepLog).takeLast(6).joinToString(" | ") { it.take(300) } }.getOrDefault("no output")

    private fun install(temp: Path, target: Path) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun daemonJvm(): List<String> = DaemonJvm.args(parsesHere = false) + "--enable-native-access=ALL-UNNAMED"

    private fun record(recording: Path) = listOf("-XX:AOTMode=record", "-XX:AOTConfiguration=$recording")

    private fun run(command: List<String>, env: Map<String, String>, dir: Path, log: Path? = null): Int {
        val process = start(command, env, dir, log)
        if (!process.waitFor(stepSeconds, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly().waitFor()
            error("${command.firstOrNull()} did not finish in $stepSeconds s")
        }
        return process.exitValue()
    }

    private fun start(command: List<String>, env: Map<String, String>, dir: Path, log: Path? = null): Process =
        ProcessBuilder(command).directory(dir.toFile()).apply { environment().putAll(env) }
            .redirectErrorStream(true)
            .redirectOutput(log?.let { ProcessBuilder.Redirect.to(it.toFile()) } ?: ProcessBuilder.Redirect.DISCARD).start()
            .also { it.outputStream.close() }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + stepSeconds * 1_000_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun tinyRepo(dir: Path): Path {
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/Greeter.kt"), "package demo\n\nclass Greeter {\n    fun greet(name: String): String = \"Hello, \$name\"\n}\n")
        Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call().use { git ->
            git.add().addFilepattern(".").call()
            val who = PersonIdent("codeloupe", "codeloupe@localhost")
            git.commit().setMessage("init").setAuthor(who).setCommitter(who).setSign(false).call()
        }
        return dir
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    // What an interrupted training of this install left: temporary caches and work directories.
    private fun removeLeftovers() {
        val own = caches.prefix.fileName.toString()
        Files.newDirectoryStream(caches.prefix.parent).use { entries ->
            for (entry in entries) if (entry.name.startsWith(own) && (".tmp-" in entry.name || ".work-" in entry.name)) delete(entry)
        }
    }

    // The caches of older builds of this install and the dynamic class-data archives the launchers made before: nothing reads them now.
    private fun removeOtherBuilds() {
        val own = caches.prefix.fileName.toString()
        val build = Regex(Regex.escape(own.substringBeforeLast('-')) + "-[0-9a-f]{12}[.].*")
        Files.newDirectoryStream(caches.prefix.parent).use { entries ->
            for (entry in entries) if (build.matches(entry.name) && !entry.name.startsWith(own)) delete(entry)
        }
        val archives = caches.prefix.parent.resolveSibling("cds")
        if (Files.isDirectory(archives)) Files.newDirectoryStream(archives, "*.jsa").use { entries -> entries.filter { build.matches(it.name) }.forEach(::delete) }
    }

    // A directory the processes just ended had open can take a moment to let go (Windows).
    private fun deleteSettled(path: Path) {
        repeat(40) {
            delete(path)
            if (!Files.exists(path)) return
            Thread.sleep(250)
        }
    }

    // Git writes its objects read-only, which Windows will not delete until the attribute is cleared.
    private fun delete(path: Path) {
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = remove(file)
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult = remove(dir)
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        }
    }

    private fun remove(entry: Path): FileVisitResult {
        entry.toFile().setWritable(true)
        runCatching { Files.deleteIfExists(entry) }
        return FileVisitResult.CONTINUE
    }

    companion object {
        /** Set for every JVM the trainer starts, so that none of them trains again. */
        const val TRAINING_ENV = "CODELOUPE_AOT_TRAINING"

        private const val MAIN_CLASS = "codeloupe.MainKt"
        private val HEADERS = mapOf("content-type" to "application/json", CodeLoupe.HEADER to "1")
    }
}
