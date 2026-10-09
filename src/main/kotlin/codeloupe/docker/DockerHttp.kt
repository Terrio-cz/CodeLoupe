package codeloupe.docker

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * One HTTP/1.1 request over a connection of [DockerEndpoint], hand-written because neither `HttpURLConnection` nor
 * the JDK client speaks to a Windows named pipe. A request per connection, `Connection: close`; the answer is read
 * whole (chunked, `Content-Length` or until the Engine closes).
 */
class DockerHttp(private val endpoint: DockerEndpoint, private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    class Reply(val status: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /**
     * Answers within [timeoutMs] or throws [DockerUnavailable]: an Engine that accepts the connection and never answers (Docker
     * Desktop while it starts, a wedged daemon) must not hold the caller, which may be the reconciler with its lock. A named
     * pipe cannot time out a read, so the exchange runs on its own thread and is abandoned, its connection closed, when the time is up.
     */
    fun request(method: String, path: String, body: ByteArray? = null, contentType: String = "application/json", timeoutMs: Long = defaultTimeoutMs): Reply {
        val opened = AtomicReference<DockerConnection?>()
        val outcome = CompletableFuture<Reply>()
        val worker = Thread({
            try {
                endpoint.connect().use { connection ->
                    opened.set(connection)
                    outcome.complete(exchange(connection, method, path, body, contentType))
                }
            } catch (e: Throwable) {
                outcome.completeExceptionally(e)
            }
        }, "docker-http")
        worker.isDaemon = true
        worker.start()
        try {
            return outcome.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            abandon(opened.get(), worker)
            throw DockerUnavailable("the Docker Engine did not answer $method $path within ${timeoutMs / 1000} s")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            abandon(opened.get(), worker)
            throw e
        }
    }

    // Closing a connection with a read pending can itself wait on Windows, so it is done away from the caller.
    private fun abandon(connection: DockerConnection?, worker: Thread) {
        val closer = Thread({ runCatching { connection?.close() }; worker.interrupt() }, "docker-http-close")
        closer.isDaemon = true
        closer.start()
    }

    private fun exchange(connection: DockerConnection, method: String, path: String, body: ByteArray?, contentType: String): Reply {
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            append("Host: docker\r\nUser-Agent: codeloupe\r\nConnection: close\r\n")
            if (body != null) append("Content-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.size).append("\r\n")
            append("\r\n")
        }
        connection.output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body != null) connection.output.write(body)
        connection.output.flush()
        return read(connection.input)
    }

    private fun read(raw: InputStream): Reply {
        val input = raw.buffered()
        val status = line(input)?.split(' ', limit = 3)?.getOrNull(1)?.toIntOrNull() ?: throw DockerUnavailable("the Docker Engine sent no HTTP status")
        val headers = HashMap<String, String>()
        while (true) {
            val header = line(input) ?: throw DockerUnavailable("the Docker Engine closed the connection inside the headers")
            if (header.isEmpty()) break
            headers[header.substringBefore(':').trim().lowercase()] = header.substringAfter(':').trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> chunked(input)
            headers["content-length"]?.toIntOrNull() != null -> input.readNBytes(headers.getValue("content-length").toInt())
            else -> input.readAllBytes()
        }
        return Reply(status, body)
    }

    private fun chunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = line(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw DockerUnavailable("the Docker Engine sent a broken chunk")
            if (size == 0) break
            out.write(input.readNBytes(size))
            line(input)
        }
        return out.toByteArray()
    }

    // One CRLF-terminated line without its terminator; null at the end of the stream.
    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1).trimEnd('\r')
            out.write(b)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
