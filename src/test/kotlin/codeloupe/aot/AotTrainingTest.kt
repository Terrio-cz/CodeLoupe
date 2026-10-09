package codeloupe.aot

import codeloupe.TestRepos
import codeloupe.cli.CliJvm
import codeloupe.cli.DaemonJvm
import codeloupe.cli.LocalHttp
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The caches of an install, end to end with real JVMs on the installed jars (`installDist`): two first calls at once both
 * run and leave one pair of valid caches, a call with them works also from a moved copy of the install and with damaged
 * files, and a daemon starts with its cache. Takes about half a minute.
 */
class AotTrainingTest {
    private val install = Path.of(System.getProperty("codeloupe.projectDir"), "build", "install", "codeloupe")
    private val jar = Files.list(install.resolve("lib")).use { files -> files.toList().single { it.name.matches(Regex("codeloupe-.*[.]jar")) } }
    private val home = TestRepos.tmpDir("aot-training")
    private val prefix = home.resolve("aot").resolve("C__test_codeloupe-0123456789ab")
    private val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()

    private fun cli(jar: Path, vararg extra: String, args: List<String> = listOf("--version")): ProcessBuilder =
        ProcessBuilder(listOf(java) + CliJvm.args + extra + listOf("-jar", jar.toString()) + args)
            .apply { environment()["CODELOUPE_HOME"] = home.toString() }
            .redirectErrorStream(true)

    private fun output(builder: ProcessBuilder): Pair<Int, String> {
        val process = builder.start().also { it.outputStream.close() }
        val text = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return process.exitValue() to text
    }

    @Test
    fun `two first calls at once run, one trainer makes the caches, and calls with them work moved, damaged or not`() {
        Files.createDirectories(prefix.parent)
        val caches = AotCaches(prefix)
        // Two first calls at once find no cache and write nothing.
        val calls = (1..2).map { cli(jar).start().also { p -> p.outputStream.close() } }
        for (call in calls) {
            val text = call.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
            assertTrue(call.waitFor(60, TimeUnit.SECONDS))
            assertEquals(0, call.exitValue(), text)
            assertTrue(text.startsWith("codeloupe version"), text)
        }
        // Two daemons that start together and find no caches start one trainer, which makes them.
        val starters = (1..2).map { Thread.ofPlatform().start { AotLauncher.startTraining(caches, jar.toString(), AotLauncher::spawn) } }
        starters.forEach(Thread::join)
        val deadline = System.currentTimeMillis() + 240_000
        while (!caches.isReady() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue(caches.isReady(), "no caches after four minutes; failure note: ${runCatching { Files.readString(caches.failed) }.getOrNull()}")
        while ((Files.exists(caches.lock) || Files.exists(caches.trainer)) && System.currentTimeMillis() < deadline) Thread.sleep(200)

        val files = Files.list(prefix.parent).use { entries -> entries.map { it.name }.toList().sorted() }
        assertEquals(listOf("C__test_codeloupe-0123456789ab.cli.aot", "C__test_codeloupe-0123456789ab.daemon.aot", "C__test_codeloupe-0123456789ab.ready"), files, "one pair of caches, nothing left over")

        val (code, text) = output(cli(jar, "-XX:AOTCache=${caches.cli}"))
        assertEquals(0 to true, code to text.startsWith("codeloupe version"), text)

        // An install copied elsewhere (other path, other modification times) runs with the cache or without it, silently.
        val moved = home.resolve("moved")
        Files.walk(install).use { paths -> paths.forEach { Files.copy(it, moved.resolve(install.relativize(it).toString()), StandardCopyOption.REPLACE_EXISTING) } }
        val (movedCode, movedText) = output(cli(moved.resolve("lib").resolve(jar.name), "-XX:AOTCache=${caches.cli}"))
        assertEquals(0 to true, movedCode to movedText.startsWith("codeloupe version"), movedText)

        val original = Files.readAllBytes(caches.cli)
        val damaged = listOf("cut" to original.copyOf(original.size / 3), "empty" to ByteArray(0), "garbage" to ByteArray(2_000_000) { 7 })
        for ((name, content) in damaged) {
            val file = home.resolve("damaged-$name.aot").also { Files.write(it, content) }
            val (damagedCode, damagedText) = output(cli(jar, "-XX:AOTCache=$file"))
            assertEquals(0 to true, damagedCode to damagedText.startsWith("codeloupe version"), "$name: $damagedText")
        }
        val (missingCode, missingText) = output(cli(jar, "-XX:AOTCache=${home.resolve("nothing.aot")}"))
        assertEquals(0 to true, missingCode to missingText.startsWith("codeloupe version"), missingText)

        daemonStartsWith(caches.daemonFlags())
        daemonStartsWith(listOf("-XX:AOTCache=${home.resolve("damaged-garbage.aot")}"))
    }

    private fun daemonStartsWith(flags: List<String>) {
        assertTrue(flags.isNotEmpty())
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val daemonHome = Files.createTempDirectory(home, "daemon")
        val command = listOf(java) + DaemonJvm.args() + flags + listOf("--enable-native-access=ALL-UNNAMED", "-cp", jar.toString(), "codeloupe.MainKt", "daemon", "--home", daemonHome.toString(), "--port", port.toString())
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(daemonHome.resolve("out.txt").toFile()).start().also { it.outputStream.close() }
        val http = LocalHttp("http://127.0.0.1:$port")
        try {
            val deadline = System.currentTimeMillis() + 60_000
            var up = false
            while (!up && process.isAlive && System.currentTimeMillis() < deadline) {
                up = runCatching { http.request("GET", "/status", readTimeoutMs = 500).status == 200 }.getOrDefault(false)
                if (!up) Thread.sleep(50)
            }
            assertTrue(up, "the daemon did not answer with $flags: ${runCatching { Files.readString(daemonHome.resolve("out.txt")) }.getOrNull()}")
        } finally {
            runCatching { http.request("POST", "/shutdown?force=1", headers = mapOf("x-codeloupe" to "1"), readTimeoutMs = 5_000) }
            if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
