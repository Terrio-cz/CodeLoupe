package codeloupe.docker

import codeloupe.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /resources`: the Docker inventory as [ResourceReport]. Always 200; what could not be read is in `problems`. */
fun Route.resourceRoutes(inventory: ResourceInventory) {
    get("/resources") {
        call.respondText(JsonFormat.json.encodeToString(ResourceReport.serializer(), inventory.report()), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
