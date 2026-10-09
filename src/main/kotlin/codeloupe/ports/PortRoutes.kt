package codeloupe.ports

import codeloupe.JsonFormat
import codeloupe.daemon.receiveBoundedText
import codeloupe.workspace.WorkspaceRef
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * `GET /ports`: the allocations with what holds each port ([PortReport]). `POST /ports/allocate` with
 * `{ "repo", "workspace", "name" }` records and answers the port of that name in that workspace; `POST /ports/free`
 * with `{ "repo", "workspace", "name"? }` forgets one port or all of the workspace's.
 */
fun Route.portRoutes(ports: PortRegistry) {
    get("/ports") {
        respond(call, HttpStatusCode.OK, JsonFormat.json.encodeToString(PortReport.serializer(), ports.status()))
    }
    post("/ports/allocate") {
        val (ref, body) = read(call) ?: return@post
        val name = text(body, "name") ?: return@post respond(call, HttpStatusCode.BadRequest, error("a port needs a name"))
        try {
            respond(call, HttpStatusCode.OK, JsonFormat.json.encodeToString(PortAllocation.serializer(), ports.allocate(ref, name)))
        } catch (e: IllegalStateException) {
            respond(call, HttpStatusCode.Conflict, error(e.message.orEmpty()))
        } catch (e: IllegalArgumentException) {
            respond(call, HttpStatusCode.BadRequest, error(e.message.orEmpty()))
        }
    }
    post("/ports/free") {
        val (ref, body) = read(call) ?: return@post
        respond(call, HttpStatusCode.OK, buildJsonObject { put("freed", ports.free(ref, text(body, "name"))) }.toString())
    }
}

private suspend fun read(call: ApplicationCall): Pair<WorkspaceRef, JsonObject>? {
    val body = runCatching { call.receiveBoundedText().ifBlank { "{}" }.let { JsonFormat.json.parseToJsonElement(it).jsonObject } }.getOrNull()
    val repo = body?.let { text(it, "repo") }
    val workspace = body?.let { text(it, "workspace") }
    if (body == null || repo == null || workspace == null) {
        respond(call, HttpStatusCode.BadRequest, error("""send {"repo": "<repository>", "workspace": "<worktree name>", "name": "<port name>"}"""))
        return null
    }
    return WorkspaceRef(repo, workspace) to body
}

private fun text(body: JsonObject, key: String): String? = (body[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun error(message: String) = buildJsonObject { put("error", message) }.toString()

private suspend fun respond(call: ApplicationCall, status: HttpStatusCode, body: String) = call.respondText(body, ContentType.Application.Json, status)
