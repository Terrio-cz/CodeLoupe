package codeloupe.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalHttpTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/echo") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val answer = "${exchange.requestMethod} ${exchange.requestHeaders.getFirst("x-codeloupe")} $body".toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        createContext("/refuse") { exchange ->
            val answer = """{"error":"daemon has jobs"}""".toByteArray()
            exchange.sendResponseHeaders(409, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        createContext("/empty") { exchange -> exchange.sendResponseHeaders(404, -1) }
        createContext("/slow") { exchange ->
            Thread.sleep(1_000)
            exchange.sendResponseHeaders(200, -1)
        }
        start()
    }
    private val http = LocalHttp("http://127.0.0.1:${server.address.port}")

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `a POST sends its headers and its body as UTF-8`() {
        val reply = http.request("POST", "/echo", """{"q":"…→"}""", mapOf("x-codeloupe" to "1"))
        assertEquals(200, reply.status)
        assertEquals("""POST 1 {"q":"…→"}""", reply.body)
    }

    @Test
    fun `a POST without a body and a GET are sent as such`() {
        assertEquals("POST 1 ", http.request("POST", "/echo", headers = mapOf("x-codeloupe" to "1")).body)
        assertEquals("GET null ", http.request("GET", "/echo").body)
    }

    @Test
    fun `an error status is a reply with its body, not an exception`() {
        val refused = http.request("POST", "/refuse")
        assertEquals(409, refused.status)
        assertEquals("""{"error":"daemon has jobs"}""", refused.body)
        val empty = http.request("GET", "/empty")
        assertEquals(404, empty.status)
        assertEquals("", empty.body)
    }

    @Test
    fun `a daemon slower than the read timeout fails with a timeout`() {
        assertFailsWith<SocketTimeoutException> { http.request("GET", "/slow", readTimeoutMs = 200) }
        assertEquals(200, http.request("GET", "/slow").status)
    }
}
