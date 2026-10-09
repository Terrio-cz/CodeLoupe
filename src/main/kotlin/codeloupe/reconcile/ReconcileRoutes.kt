package codeloupe.reconcile

import codeloupe.JsonFormat
import codeloupe.daemon.receiveBoundedText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * `GET /reconcile`: the dry run, a [ReconcilePlan]. `POST /reconcile/run` with `{ "confirm": ["volume:…"], "workspaces": ["TER-420"], "auto": true }`
 * (all optional) attempts the `auto` entries (unless `auto` is false: a caller that shows the user only what it asks to remove says so) plus the `confirm` entries named by key or by workspace, and answers a [ReconcileRun].
 *
 * A call that confirms something must send the `planHash` of the plan the person saw (CL-169): without it the answer is 428, and if the
 * plan the daemon holds now hashes differently the answer is 409 with the current plan and nothing is removed.
 */
fun Route.reconcileRoutes(reconciler: Reconciler) {
    get("/reconcile") {
        call.respondText(JsonFormat.json.encodeToString(ReconcilePlan.serializer(), reconciler.plan()), ContentType.Application.Json, HttpStatusCode.OK)
    }
    post("/reconcile/run") {
        val body = runCatching { call.receiveBoundedText().ifBlank { "{}" }.let { JsonFormat.json.parseToJsonElement(it).jsonObject } }.getOrNull()
            ?: return@post call.respondText("""{"error":"send {\"confirm\": [keys], \"workspaces\": [names]}"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
        val confirm = strings(body, "confirm")
        val workspaces = strings(body, "workspaces")
        val shown = (body["planHash"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((confirm.isNotEmpty() || workspaces.isNotEmpty()) && shown.isNullOrBlank()) {
            return@post call.respondText(
                """{"error":"a confirm must carry the planHash of the plan that was shown: read GET /reconcile, show it, then send its planHash"}""",
                ContentType.Application.Json, PRECONDITION_REQUIRED,
            )
        }
        val run = try {
            reconciler.run("manual", auto = wantsAuto(body), confirm = confirm, workspaces = workspaces, shownPlan = shown?.takeIf { confirm.isNotEmpty() || workspaces.isNotEmpty() })
        } catch (stale: StalePlan) {
            val answer = JsonObject(mapOf("error" to JsonPrimitive("the cleanup plan changed since it was shown: nothing was removed"), "plan" to JsonFormat.json.encodeToJsonElement(ReconcilePlan.serializer(), stale.current)))
            return@post call.respondText(answer.toString(), ContentType.Application.Json, HttpStatusCode.Conflict)
        }
        call.respondText(JsonFormat.json.encodeToString(ReconcileRun.serializer(), run), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

private val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")

private fun strings(body: JsonObject, key: String): Set<String> = (body[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()

/** Whether the call also runs the `auto` entries: yes unless it says `"auto": false` (the app asks for the entries the person confirmed, nothing more). */
internal fun wantsAuto(body: JsonObject): Boolean = (body["auto"] as? JsonPrimitive)?.booleanOrNull ?: true
