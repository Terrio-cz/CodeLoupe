package codeloupe.daemon

import codeloupe.CodeLoupe
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

/**
 * Local only, never from a browser page: exact Host (defeats DNS rebinding), no Origin, and the CodeLoupe
 * header on everything but `/status` (a page cannot send it without a CORS preflight, which is refused).
 */
internal class RequestGuard(private val port: Int) {
    /** Why the request is refused, or null to let it through. */
    fun refusal(call: ApplicationCall): String? {
        val host = call.request.headers[HttpHeaders.Host].orEmpty()
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return "bad host"
        if (call.request.headers[HttpHeaders.Origin] != null) return "browser requests are not accepted"
        if (call.request.httpMethod == HttpMethod.Get && call.request.path() == "/status") return null
        if (call.request.headers[CodeLoupe.HEADER] == null) return "missing ${CodeLoupe.HEADER} header"
        // Most routes read the whole body into memory; a size that is declared too large is not read at all.
        if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BODY) return "body too large"
        return null
    }

    companion object {
        val STATUS = HttpStatusCode.Forbidden
        const val MAX_BODY = 8L * 1024 * 1024
    }
}
