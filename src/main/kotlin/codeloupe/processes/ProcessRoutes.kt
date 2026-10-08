package codeloupe.processes

import codeloupe.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /processes`: the processes that work in a workspace and the memory of each workspace, a [ProcessReport]. Always 200. */
fun Route.processRoutes(inventory: ProcessInventory) {
    get("/processes") {
        call.respondText(JsonFormat.json.encodeToString(ProcessReport.serializer(), inventory.report()), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
