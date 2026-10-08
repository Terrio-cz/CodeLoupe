package codeloupe.docker

import codeloupe.TestRepos
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DockerHttpTest {
    /** Serves [answer] once on a unix socket, handing the raw request it received to [seen]. */
    private fun serve(answer: ByteArray, seen: (String) -> Unit = {}): Pair<Path, Thread> {
        val socket = TestRepos.tmpDir("sock").resolve("engine.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
        val worker = thread(isDaemon = true) {
            server.use { s ->
                s.accept().use { channel ->
                    val input = Channels.newInputStream(channel)
                    val received = StringBuilder()
                    while (!received.endsWith("\r\n\r\n")) received.append(input.read().toChar())
                    val length = Regex("Content-Length: (\\d+)").find(received)?.groupValues?.get(1)?.toInt() ?: 0
                    received.append(String(input.readNBytes(length), Charsets.UTF_8))
                    seen(received.toString())
                    Channels.newOutputStream(channel).apply { write(answer); flush() }
                }
            }
        }
        return socket to worker
    }

    @Test
    fun `a content-length answer is read whole`() {
        val (socket, worker) = serve("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}".toByteArray())
        val reply = DockerHttp(DockerEndpoint.UnixSocket(socket.toString())).request("GET", "/_ping")
        worker.join()
        assertEquals(200, reply.status)
        assertEquals("{\"ok\":true}", reply.text)
    }

    @Test
    fun `a chunked answer is decoded and the request carries its body`() {
        var request = ""
        val (socket, worker) = serve("HTTP/1.1 201 Created\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".toByteArray()) { request = it }
        val reply = DockerHttp(DockerEndpoint.UnixSocket(socket.toString())).request("POST", "/volumes/create", """{"Name":"v"}""".toByteArray())
        worker.join()
        assertEquals(201, reply.status)
        assertEquals("hello world", reply.text)
        assertContains(request, "POST /volumes/create HTTP/1.1")
        assertContains(request, "Connection: close")
        assertContains(request, """{"Name":"v"}""")
    }

    @Test
    fun `an answer without a length is read until the engine closes`() {
        val (socket, worker) = serve("HTTP/1.1 404 Not Found\r\n\r\n{\"message\":\"no such volume\"}".toByteArray())
        val reply = DockerHttp(DockerEndpoint.UnixSocket(socket.toString())).request("GET", "/volumes/x")
        worker.join()
        assertEquals(404, reply.status)
        assertContains(reply.text, "no such volume")
    }
}
