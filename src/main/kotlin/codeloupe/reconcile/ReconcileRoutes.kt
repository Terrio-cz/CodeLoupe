package codeloupe.reconcile

import codeloupe.JsonFormat
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * `GET /reconcile`: the dry run, a [ReconcilePlan]. `POST /reconcile/run` with `{ "confirm": ["volume:…"], "workspaces": ["TER-420"] }`
 * (both optional) attempts the `auto` entries plus the `confirm` entries named by key or by workspace, and answers a [ReconcileRun].
 */
fun Route.reconcileRoutes(reconciler: Reconciler) {
    get("/reconcile") {
        call.respondText(JsonFormat.json.encodeToString(ReconcilePlan.serializer(), reconciler.plan()), ContentType.Application.Json, HttpStatusCode.OK)
    }
    post("/reconcile/run") {
        val body = runCatching { call.receiveText().ifBlank { "{}" }.let { JsonFormat.json.parseToJsonElement(it).jsonObject } }.getOrNull()
            ?: return@post call.respondText("""{"error":"send {\"confirm\": [keys], \"workspaces\": [names]}"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        val run = reconciler.run("manual", auto = true, confirm = strings(body, "confirm"), workspaces = strings(body, "workspaces"))
        call.respondText(JsonFormat.json.encodeToString(ReconcileRun.serializer(), run), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private fun strings(body: JsonObject, key: String): Set<String> = (body[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
