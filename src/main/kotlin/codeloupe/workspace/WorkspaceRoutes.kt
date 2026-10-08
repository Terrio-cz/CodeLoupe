package codeloupe.workspace

import codeloupe.JsonFormat
import codeloupe.processes.ProcessInventory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /workspaces?repo=<path>&size=1&ram=1`: the workspace registry as [WorkspaceList]. Without `repo` every known
 * repository; `size=1` adds each directory's size in bytes (a walk of the files, so seconds on a built worktree), `ram=1`
 * the memory of the processes that work in it (a read of the process table).
 */
fun Route.workspaceRoutes(workspaces: Workspaces, processes: ProcessInventory) {
    get("/workspaces") {
        val repo = call.parameters["repo"]?.takeIf { it.isNotBlank() }
        val size = call.parameters["size"] in setOf("1", "true")
        val ram = call.parameters["ram"] in setOf("1", "true")
        val list = workspaces.list(repo, size).let { if (ram) processes.withRam(it) else it }
        call.respondText(JsonFormat.json.encodeToString(WorkspaceList.serializer(), list), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
