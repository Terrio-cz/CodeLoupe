package codeloupe.daemon

import codeloupe.CodeLoupe
import codeloupe.JsonFormat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * How a refusal reaches an MCP client. Claude Code works out the headers of an MCP server once, when it connects: a
 * window that reached a daemon from before the token kept the plain header, and after that daemon is replaced every
 * call is refused with a bare 401 — which the client reports as a rejected client registration while it still shows
 * the server as connected, so nothing in it says what to do (CL-260). A refusal of `/mcp` is therefore answered as a
 * JSON-RPC error carrying the call's own id, in words that name reconnecting as the fix; the 401 of every other route
 * carries a `WWW-Authenticate` challenge, which says that a token is the problem and not a missing registration.
 */
object McpRefusal {
    /** Said on the route itself, where the client shows it in place of the tool's answer. */
    const val RECONNECT = "this window's connection to CodeLoupe is stale: it holds the headers of a daemon that no longer runs " +
        "(the daemon was replaced, by an update or by `codeloupe start`). Reconnect the codeloupe MCP server (/mcp, Reconnect) " +
        "or open a new window; it is not a fault of the call."

    /** The challenge on a 401: a token, not an authorization server to register with. */
    const val CHALLENGE = "Bearer realm=\"${CodeLoupe.NAME}\", error=\"invalid_token\""

    /**
     * The JSON-RPC error answer for the request in [body], carrying its id so the client matches it to its call (null
     * when the body is gone or not a request, which JSON-RPC allows for an error that names no id).
     */
    fun answer(body: String?, said: String): JsonObject {
        val id = body?.let { runCatching { JsonFormat.json.parseToJsonElement(it).jsonObject["id"] }.getOrNull() } ?: JsonNull
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            putJsonObject("error") {
                put("code", INVALID_REQUEST)
                put("message", "$said $RECONNECT")
            }
        }
    }

    /** JSON-RPC's own code for a request the server will not act on; the message carries the reason. */
    private const val INVALID_REQUEST = -32600
}
