package codeloupe.docker

import codeloupe.TestRepos
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import kotlin.concurrent.thread

/**
 * A Docker Engine on a unix socket that answers every request through [handler] (method, path) -> (status, JSON body), or never
 * answers when the handler returns null. Requests are counted per `METHOD path`.
 */
class FakeDockerEngine(private val handler: (String, String) -> Pair<Int, String>?) : AutoCloseable {
    private val socket = TestRepos.tmpDir("engine").resolve("engine.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket))
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    val endpoint = DockerEndpoint.UnixSocket(socket.toString())
    val api get() = DockerApi(endpoint)

    init {
        thread(isDaemon = true) {
            while (server.isOpen) {
                val channel = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(channel) }
            }
        }
    }

    private fun serve(channel: java.nio.channels.SocketChannel) {
        channel.use {
            val input = Channels.newInputStream(channel)
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return
                head.append(b.toChar())
            }
            val line = head.lineSequence().first().split(' ')
            requests += "${line[0]} ${line[1]}"
            val reply = handler(line[0], line[1])
            if (reply == null) {
                Thread.sleep(HANG_MS)
                return
            }
            val body = reply.second.toByteArray()
            Channels.newOutputStream(channel).apply {
                write("HTTP/1.1 ${reply.first} X\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }
    }

    override fun close() {
        server.close()
    }

    private companion object {
        const val HANG_MS = 60_000L
    }
}
