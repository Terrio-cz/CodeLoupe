package codeloupe.workspace

import codeloupe.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /workspaces?repo=<path>&size=1`: the workspace registry as [WorkspaceList]. Without `repo` every known
 * repository; `size=1` adds each directory's size in bytes (a walk of the files, so seconds on a built worktree).
 */
fun Route.workspaceRoutes(workspaces: Workspaces) {
    get("/workspaces") {
        val repo = call.parameters["repo"]?.takeIf { it.isNotBlank() }
        val size = call.parameters["size"] in setOf("1", "true")
        call.respondText(JsonFormat.json.encodeToString(WorkspaceList.serializer(), workspaces.list(repo, size)), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
