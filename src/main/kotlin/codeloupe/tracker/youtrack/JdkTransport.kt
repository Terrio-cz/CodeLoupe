package codeloupe.tracker.youtrack

import codeloupe.tracker.TokenSource
import codeloupe.tracker.TrackerException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** HTTPS with a bearer token read per request; failures carry the path, never the token. */
class JdkTransport(private val baseUrl: String, private val token: TokenSource, private val timeout: Duration = Duration.ofSeconds(30)) : HttpTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()

    override fun get(path: String): HttpReply {
        val secret = token.read()
        val where = path.substringBefore('?')
        return try {
            val request = HttpRequest.newBuilder(URI(baseUrl + path))
                .timeout(timeout)
                .header("Authorization", "Bearer $secret")
                .header("Accept", "application/json")
                .GET()
                .build()
            // A body may quote the request (a proxy's echo page); it never leaves here with the token in it.
            client.send(request, HttpResponse.BodyHandlers.ofString()).let { HttpReply(it.statusCode(), it.body().replace(secret, "[redacted]")) }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TrackerException("interrupted on $where")
        } catch (e: IllegalArgumentException) {
            // Its message can quote the header value.
            throw TrackerException("invalid request for $where")
        } catch (e: Exception) {
            throw TrackerException("${host()} unreachable on $where: ${e::class.simpleName}: ${e.message.orEmpty().replace(secret, "[redacted]")}")
        }
    }

    private fun host() = URI(baseUrl).host ?: "tracker"
}
