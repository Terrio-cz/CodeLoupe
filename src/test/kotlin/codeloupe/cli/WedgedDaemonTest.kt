package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.DaemonInfo
import codeloupe.platform.IsoTime
import codeloupe.platform.JavaProcess
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A daemon whose process lives while its port gives no answer (CL-238): named by `status`, ended by `start` and `stop`. */
class WedgedDaemonTest {
    private val config = Config(TestRepos.tmpDir("wedged-home"), ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)
    private val started = ArrayList<Process>()
    private var server: HttpServer? = null

    @AfterTest
    fun cleanup() {
        started.forEach { it.destroyForcibly() }
        server?.stop(0)
    }

    /** The stopped-accept stub: bound to our port, never accepting; daemon.json names it as a daemon writes it. */
    private fun stuckDaemon(): Process {
        val process = ProcessBuilder(JavaProcess.command(DaemonStubs.MAIN, listOf("-Xmx32m"), listOf("stuck", config.port.toString()))).redirectErrorStream(true).start()
        started += process
        assertEquals("bound", process.inputReader().readLine())
        writeInfo(process.pid(), Instant.now())
        return process
    }

    private fun writeInfo(pid: Long, at: Instant) =
        Files.writeString(config.home.resolve("daemon.json"), JsonFormat.json.encodeToString(DaemonInfo.serializer(), DaemonInfo(pid, config.port, "0", IsoTime.of(at))))

    /** What the detached start stands for here: a daemon that answers `/status` and names itself in daemon.json. */
    private fun startFake() {
        val pid = ProcessHandle.current().pid()
        val fake = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
        fake.createContext("/status") { exchange ->
            val body = """{"name":"codeloupe","pid":$pid,"port":${config.port},"home":"${config.home.toString().replace("\\", "\\\\")}"}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        fake.start()
        server = fake
        writeInfo(pid, Instant.now())
    }

    @Test
    fun `status names a wedged daemon instead of saying it is not running`() {
        val stuck = stuckDaemon()
        val result = StatusCommand(load = { config }).test()
        assertEquals(3, result.statusCode)
        assertContains(result.output, "wedged: the daemon (pid ${stuck.pid()}) is alive but does not answer on 127.0.0.1:${config.port}")
    }

    @Test
    fun `a call that needs the daemon fails naming the wedged one instead of starting another`() {
        stuckDaemon()
        var launched = false
        val error = assertFailsWith<IllegalStateException> { DaemonClient(config, launcher = { launched = true }).ensureDaemon() }
        assertContains(error.message.orEmpty(), "it is wedged")
        assertFalse(launched)
    }

    @Test
    fun `start ends the wedged daemon and starts a new one`() {
        val stuck = stuckDaemon()
        val result = StartCommand(load = { config }, client = { DaemonClient(it, launcher = ::startFake) }).test()
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "ended the wedged daemon pid ${stuck.pid()}: alive, 127.0.0.1:${config.port} did not answer")
        assertContains(result.output, "running pid ${ProcessHandle.current().pid()}")
        assertFalse(stuck.isAlive)
    }

    @Test
    fun `stop ends a wedged daemon it cannot ask`() {
        val stuck = stuckDaemon()
        val result = StopCommand(load = { config }).test()
        assertContains(result.output, "ended the wedged daemon pid ${stuck.pid()}")
        assertTrue(stuck.waitFor(10, TimeUnit.SECONDS))
    }

    @Test
    fun `a pid the system gave to another process is not taken for the daemon`() {
        writeInfo(4242, Instant.parse("2026-10-12T10:00:00Z"))
        val other = WedgedDaemon.Seen(alive = true, start = Instant.parse("2026-10-12T11:00:00Z"), commandLine = "codeloupe daemon")
        assertNull(WedgedDaemon.find(config.home, config.port) { other })
        val older = WedgedDaemon.Seen(alive = true, start = Instant.parse("2026-10-11T10:00:00Z"), commandLine = "codeloupe daemon")
        assertNull(WedgedDaemon.find(config.home, config.port) { older })
        val unknownStart = WedgedDaemon.Seen(alive = true, start = null, commandLine = "C:/Windows/notepad.exe")
        assertNull(WedgedDaemon.find(config.home, config.port) { unknownStart })
        val ours = WedgedDaemon.Seen(alive = true, start = Instant.parse("2026-10-12T09:59:59Z"), commandLine = null)
        assertNotNull(WedgedDaemon.find(config.home, config.port) { ours })
        assertNull(WedgedDaemon.find(config.home, config.port + 1) { ours }, "daemon.json of another port")
        assertNull(WedgedDaemon.find(config.home, config.port) { WedgedDaemon.Seen(false, null, null) }, "the process has ended")
    }

    @Test
    fun `a daemon JVM that runs out of heap ends`() {
        val flags = DaemonJvm.args().filterNot { it.startsWith("-Xmx") || it.startsWith("-Xms") || it.startsWith("-Xmn") } + "-Xmx32m"
        val process = ProcessBuilder(JavaProcess.command(DaemonStubs.MAIN, flags, listOf("oom"))).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        started += process
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the JVM still runs after its heap ran out")
        assertEquals(3, process.exitValue(), "-XX:+ExitOnOutOfMemoryError ends the JVM with status 3")
    }
}
