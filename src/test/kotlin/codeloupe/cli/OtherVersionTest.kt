package codeloupe.cli

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import codeloupe.TestRepos
import codeloupe.config.Config
import codeloupe.daemon.Daemon
import codeloupe.daemon.DaemonInfo
import codeloupe.platform.IsoTime
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A daemon of another version must never be spoken to (CL-260): one left from a deploy an upgrade replaced served the
 * port for hours while every command treated it as ours and the app showed empty screens.
 */
class OtherVersionTest {
    /** The version the daemon left on the port reports. Not `0.1.0`: that is what a build without a release tag is. */
    private val OLD = "0.0.1"

    private val config = Config(TestRepos.tmpDir("version-home"), ServerSocket(0).use { it.localPort }, 60_000, 120_000, 512, null)
    private val servers = ArrayList<HttpServer>()

    @AfterTest
    fun cleanup() = servers.forEach { it.stop(0) }

    /**
     * A daemon on our port that reports [version] and runs [runningJobs] jobs. It answers `/shutdown` and then stops
     * listening, as the real one does, so a replacement can take the port.
     */
    private fun fakeDaemon(version: String, jar: String? = null, runningJobs: Int = 0): HttpServer {
        val pid = ProcessHandle.current().pid()
        val home = config.home.toString().replace("\\", "\\\\")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", config.port), 0)
        server.createContext("/status") { exchange ->
            val body = """
                {"name":"codeloupe","version":"$version","pid":$pid,"port":${config.port},"home":"$home",
                 ${jar?.let { """"jar":"${it.replace("\\", "\\\\")}",""" }.orEmpty()}
                 "jobs":{"running":$runningJobs,"queued":0,"policyHook":false,"slots":[]}}
            """.trimIndent().replace("\n", "").toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/shutdown") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
            // Off the handler's own thread: stop(0) waits for it to return.
            Thread.ofVirtual().start { server.stop(0) }
        }
        server.start()
        servers += server
        Files.writeString(
            config.home.resolve("daemon.json"),
            JsonFormat.json.encodeToString(DaemonInfo.serializer(), DaemonInfo(pid, config.port, version, IsoTime.of(Instant.now()))),
        )
        return server
    }

    /** What the detached start stands for here: a daemon of our own version takes the port. */
    private fun startOurs() {
        fakeDaemon(CodeLoupe.VERSION)
    }

    @Test
    fun `a daemon of our own version is used as it is`() {
        fakeDaemon(CodeLoupe.VERSION)
        var launched = false
        val status = DaemonClient(config, launcher = { launched = true }).ensureDaemon()
        assertEquals(CodeLoupe.VERSION, status["version"]?.jsonPrimitive?.content)
        assertFalse(launched, "a daemon of our version must not be replaced")
    }

    @Test
    fun `a call that needs the daemon fails naming both versions and the jar it runs`() {
        fakeDaemon(OLD, jar = "C:\\deploys\\app2\\lib\\codeloupe-0.1.0.jar")
        var launched = false
        val error = assertFailsWith<IllegalStateException> { DaemonClient(config, launcher = { launched = true }).ensureDaemon() }
        assertContains(error.message.orEmpty(), "served by CodeLoupe $OLD")
        assertContains(error.message.orEmpty(), "codeloupe-0.1.0.jar")
        assertContains(error.message.orEmpty(), "not ${CodeLoupe.VERSION}")
        assertFalse(launched, "nothing may be started behind a daemon that still holds the port")
    }

    @Test
    fun `start replaces a daemon of another version`() {
        fakeDaemon(OLD)
        val result = StartCommand(load = { config }, client = { DaemonClient(it, launcher = ::startOurs) }).test()
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.output, "replacing the daemon of another version")
        assertContains(result.output, "served by CodeLoupe $OLD")
        assertContains(result.output, "running pid ${ProcessHandle.current().pid()}")
        assertEquals(2, servers.size, "the old daemon was stopped and one of our version started")
    }

    @Test
    fun `start keeps a daemon of another version that runs jobs, and force replaces it anyway`() {
        fakeDaemon(OLD, runningJobs = 2)
        val refused = StartCommand(load = { config }, client = { DaemonClient(it, launcher = ::startOurs) }).test()
        assertEquals(1, refused.statusCode, refused.output)
        assertContains(refused.output, "it runs 2 job(s) that replacing it would end")
        assertContains(refused.output, "codeloupe start --force")

        val forced = StartCommand(load = { config }, client = { DaemonClient(it, launcher = ::startOurs) }).test("--force")
        assertEquals(0, forced.statusCode, forced.output)
        assertContains(forced.output, "running pid ${ProcessHandle.current().pid()}")
    }

    @Test
    fun `status prints the daemon's answer and then calls the version a fault`() {
        fakeDaemon(OLD, jar = "/opt/old/lib/codeloupe-0.1.0.jar")
        val result = StatusCommand(load = { config }).test()
        assertEquals(4, result.statusCode, result.output)
        assertContains(result.output, "\"version\": \"$OLD\"")
        assertContains(result.output, "codeloupe-0.1.0.jar")
        assertContains(result.output, "run `codeloupe start` to replace it")
    }

    @Test
    fun `status of our own daemon stays a plain answer and names the jar it runs`() {
        val daemon = Daemon.start(config)
        try {
            val result = StatusCommand(load = { config }).test()
            assertEquals(0, result.statusCode, result.output)
            val jar = assertNotNull(daemon.status().jar, "the daemon must say which code it runs")
            assertContains(result.output, "\"jar\"")
            assertTrue(Files.exists(Path.of(jar)), "the jar path must be real: $jar")
        } finally {
            daemon.stop()
        }
    }
}
