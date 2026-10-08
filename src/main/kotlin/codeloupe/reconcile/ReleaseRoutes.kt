package codeloupe.reconcile

import codeloupe.JsonFormat
import codeloupe.workspace.WorkspaceRef
import codeloupe.workspace.Workspaces
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * `POST /workspaces/release` with `{ "target": "<worktree directory | worktree name | task id>", "repo": "<path in the repository>" }`
 * (`repo` optional) marks the workspace released and answers at once: it only writes a small file and starts the cleanup
 * in the background ([onReleased]), so it does not depend on Docker or on a lock. `GET /workspaces/releases` answers
 * `{ "items": [...] }`: the released workspaces with what is left to clean.
 */
fun Route.releaseRoutes(workspaces: Workspaces, releases: ReleaseStore, reconciler: Reconciler, onReleased: (WorkspaceRef) -> Unit) {
    post("/workspaces/release") {
        val body = runCatching { call.receiveText().ifBlank { "{}" }.let { JsonFormat.json.parseToJsonElement(it).jsonObject } }.getOrNull()
        val target = (body?.get("target") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (body == null || target == null) return@post respond(call, HttpStatusCode.BadRequest, error("""send {"target": "<worktree directory, name or task id>", "repo": "<path>"}"""))
        val repo = (body["repo"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val ref = try {
            workspaces.resolve(target, repo)
        } catch (e: IllegalArgumentException) {
            return@post respond(call, HttpStatusCode.BadRequest, error(e.message.orEmpty()))
        }
        val release = releases.mark(ref)
        onReleased(ref)
        respond(
            call, HttpStatusCode.OK,
            buildJsonObject {
                put("repo", release.repo)
                put("workspace", release.workspace)
                put("at", release.at)
            },
        )
    }
    get("/workspaces/releases") {
        respond(call, HttpStatusCode.OK, buildJsonObject { put("items", JsonFormat.json.encodeToJsonElement(ListSerializer(ReleaseStatus.serializer()), reconciler.releaseStatus())) })
    }
}

private fun error(message: String) = buildJsonObject { put("error", message) }

private suspend fun respond(call: io.ktor.server.application.ApplicationCall, status: HttpStatusCode, body: JsonObject) =
    call.respondText(body.toString(), ContentType.Application.Json, status)
