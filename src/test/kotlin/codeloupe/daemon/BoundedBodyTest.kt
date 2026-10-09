package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.TestRepos
import codeloupe.config.Config
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/** A chunked body declares no length, so only the reader can stop it. */
class BoundedBodyTest {
    private class Endless(private val bytes: Long) : InputStream() {
        private var sent = 0L
        override fun read(): Int = if (sent++ < bytes) 'a'.code else -1
    }

    private fun chunked(port: Int, path: String, bytes: Long): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header(CodeLoupe.HEADER, "1").header(CodeLoupe.TOKEN_HEADER, TestToken.of(port))
            .POST(HttpRequest.BodyPublishers.ofInputStream { Endless(bytes) }).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `a chunked body past the limit is refused on every route that reads its text`() {
        val port = ServerSocket(0).use { it.localPort }
        val daemon = Daemon.start(Config(TestRepos.tmpDir("home"), port, 60_000, 120_000, 512, null))
        try {
            for (path in listOf("/jobs", "/webhooks", "/ports/allocate", "/ports/free", "/workspaces/release", "/reconcile/run")) {
                assertEquals(400, chunked(port, path, RequestGuard.MAX_BODY + 1).statusCode(), path)
            }
        } finally {
            daemon.stop()
        }
    }
}
