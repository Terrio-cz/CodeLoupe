package codeloupe.secrets

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * `GET /env/values?workspace=&repository=&names=A,B`: the values an MCP server or script needs, fetched in its own
 * process. The caller presents the token of `<home>/secrets/api-token.env` (readable by this user only) in `x-codeloupe-env-token`
 * and says who it is in `x-codeloupe-used-by`, which lands in the metadata of every name it fetched. Not a tool: it is not
 * in the MCP catalog and no agent-facing surface returns its answer.
 */
fun Route.envRoutes(access: SecretAccess) {
    get("/env/values") {
        val presented = call.request.headers[TOKEN_HEADER].orEmpty()
        if (presented.isEmpty() || !MessageDigest.isEqual(presented.toByteArray(), access.token().toByteArray())) return@get respond(call, HttpStatusCode.Unauthorized, "bad or missing $TOKEN_HEADER")
        val store = access.store ?: return@get respond(call, HttpStatusCode.ServiceUnavailable, "no secret store: ${access.problem}")
        val names = call.request.queryParameters["names"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val chain = SecretStore.chain(call.request.queryParameters["workspace"], call.request.queryParameters["repository"])
        val usedBy = call.request.headers[USED_BY_HEADER]?.take(80)?.takeIf { it.isNotBlank() } ?: "env api"
        val values = store.resolve(chain, usedBy, names)
        val body: JsonObject = buildJsonObject { put("values", JsonObject(values.mapValues { JsonPrimitive(it.value.value) })) }
        call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private suspend fun respond(call: ApplicationCall, status: HttpStatusCode, message: String) =
    call.respondText(buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, status)

const val TOKEN_HEADER = "x-codeloupe-env-token"
const val USED_BY_HEADER = "x-codeloupe-used-by"
