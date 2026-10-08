package codeloupe.docker

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One HTTP/1.1 request over a connection of [DockerEndpoint], hand-written because neither `HttpURLConnection` nor
 * the JDK client speaks to a Windows named pipe. A request per connection, `Connection: close`; the answer is read
 * whole (chunked, `Content-Length` or until the Engine closes).
 */
class DockerHttp(private val endpoint: DockerEndpoint) {
    class Reply(val status: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    fun request(method: String, path: String, body: ByteArray? = null, contentType: String = "application/json"): Reply =
        endpoint.connect().use { connection ->
            val head = buildString {
                append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                append("Host: docker\r\nUser-Agent: codeloupe\r\nConnection: close\r\n")
                if (body != null) append("Content-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.size).append("\r\n")
                append("\r\n")
            }
            connection.output.write(head.toByteArray(Charsets.ISO_8859_1))
            if (body != null) connection.output.write(body)
            connection.output.flush()
            read(connection.input)
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
}
