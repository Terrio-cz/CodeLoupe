package codeloupe.daemon

import codeloupe.CodeLoupe
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicLong

/**
 * Local only, never from a browser page, and only for a caller that holds the token. In order:
 *
 * - exact Host (defeats DNS rebinding), no Origin, and the CodeLoupe header on everything but `/status` (a page cannot send it without a
 *   CORS preflight, which is refused);
 * - the token of `<home>/daemon.token` in `x-codeloupe-token` on every route that acts for the user or shows what the user did: a wrong
 *   token is refused wherever it is sent. The read-only code queries (`/mcp`, `/api/<tool>` of a tool that does not [Tool.mutating], `/hook`) take
 *   a caller without one unless [strict], so that an MCP entry written before the token existed keeps working; a mutating tool called that way is
 *   refused with the way to get the token. `/status` stays open, and `/env/values` has the token of its own.
 */
internal class RequestGuard(
    private val port: Int,
    private val token: DaemonToken,
    private val strict: Boolean,
    private val mutatingTools: () -> Set<String>,
) {
    class Refusal(val status: HttpStatusCode, val message: String)

    private enum class Tier { OPEN, OWN, READ, TOKEN }

    private val withoutToken = AtomicLong()
    @Volatile private var lastWithoutToken: Long = 0

    /** What `/status` shows of the above: whether `strict` is on and how many read-only calls came without the token. */
    fun summary(): AuthStatus = AuthStatus(strict = strict, withoutToken = withoutToken.get(), lastWithoutTokenMs = lastWithoutToken.takeIf { it > 0 })

    /** Why the request is refused, or null to let it through; a request that carries the right token is marked [AUTHENTICATED]. */
    fun refusal(call: ApplicationCall): Refusal? {
        val host = call.request.headers[HttpHeaders.Host].orEmpty()
        if (host != "127.0.0.1:$port" && host != "localhost:$port") return forbidden("bad host")
        if (call.request.headers[HttpHeaders.Origin] != null) return forbidden("browser requests are not accepted")
        val tier = tier(call)
        if (tier == Tier.OPEN) return null
        if (call.request.headers[CodeLoupe.HEADER] == null) return forbidden("missing ${CodeLoupe.HEADER} header")
        // Most routes read the whole body into memory; a size that is declared too large is not read at all.
        if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BODY) return forbidden("body too large")
        if (tier == Tier.OWN) return null
        val presented = call.request.headers[CodeLoupe.TOKEN_HEADER]
        val authenticated = token.matches(presented)
        call.attributes.put(AUTHENTICATED, authenticated)
        if (presented != null && !authenticated) return Refusal(HttpStatusCode.Unauthorized, "wrong ${CodeLoupe.TOKEN_HEADER}: read it again from daemon.token in the daemon's directory")
        if (authenticated) return null
        if (tier == Tier.TOKEN || strict) return Refusal(HttpStatusCode.Unauthorized, NEEDS_TOKEN)
        withoutToken.incrementAndGet()
        lastWithoutToken = System.currentTimeMillis()
        return null
    }

    private fun forbidden(message: String) = Refusal(HttpStatusCode.Forbidden, message)

    private fun tier(call: ApplicationCall): Tier {
        val path = call.request.path()
        val method = call.request.httpMethod
        return when {
            method == HttpMethod.Get && path == "/status" -> Tier.OPEN
            method == HttpMethod.Get && path == "/env/values" -> Tier.OWN
            path == "/mcp" || path == "/hook" -> Tier.READ
            method == HttpMethod.Post && path.startsWith("/api/") -> if (path.removePrefix("/api/") in mutatingTools()) Tier.TOKEN else Tier.READ
            else -> Tier.TOKEN
        }
    }

    companion object {
        val AUTHENTICATED = AttributeKey<Boolean>("codeloupe.authenticated")
        const val MAX_BODY = 8L * 1024 * 1024

        /** What a caller is told, and what the MCP client shows for a tool that needs the token. */
        const val NEEDS_TOKEN = "this needs the daemon token (${CodeLoupe.TOKEN_HEADER}, from daemon.token in the daemon's directory): " +
            "update the CodeLoupe app, CLI and plugin to the same version, or run `codeloupe mcp-config` and use the entry it prints"

        fun authenticated(call: ApplicationCall): Boolean = call.attributes.getOrNull(AUTHENTICATED) == true
    }
}
