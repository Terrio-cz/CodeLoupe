package codeloupe.know

import codeloupe.JsonFormat
import codeloupe.daemon.receiveBoundedText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The knowledge store, behind the daemon token like every route that is not a read-only code query.
 * `GET /know/search?q=&scope=&kind=&limit=&all=1`: the best facts as compact lines. `POST /know`: one [FactDraft], or `{"facts": [...]}`.
 * `GET /know/{id}`: one fact. `GET /know/stale`: reads every anchor and answers the facts that went stale. `POST /know/{id}/verify`: the fact holds now.
 */
fun Route.knowRoutes(know: Know) {
    get("/know/search") {
        val q = call.parameters["q"].orEmpty()
        if (q.isBlank()) return@get call.fail(HttpStatusCode.BadRequest, "send ?q=<question or words>")
        call.answering {
            val limit = call.parameters["limit"]?.toIntOrNull() ?: Know.DEFAULT_LIMIT
            val hits = know.search(q, call.parameters["scope"], call.parameters["kind"], limit, call.parameters["all"] == "1")
            call.respond(HttpStatusCode.OK, JsonFormat.json.encodeToString(KnowHits.serializer(), KnowHits(hits)))
        }
    }
    get("/know/stale") {
        call.answering { call.respond(HttpStatusCode.OK, JsonFormat.json.encodeToString(StaleReport.serializer(), know.stale())) }
    }
    get("/know/{id}") {
        val fact = know.get(call.parameters["id"].orEmpty()) ?: return@get call.fail(HttpStatusCode.NotFound, "no such fact")
        call.respond(HttpStatusCode.OK, JsonFormat.json.encodeToString(Fact.serializer(), fact))
    }
    post("/know/{id}/verify") {
        call.answering {
            val result = know.verify(call.parameters["id"].orEmpty()) ?: return@answering call.fail(HttpStatusCode.NotFound, "no such fact")
            call.respond(if (result.verified) HttpStatusCode.OK else HttpStatusCode.Conflict, JsonFormat.json.encodeToString(VerifyResult.serializer(), result))
        }
    }
    post("/know") {
        call.answering {
            val body = JsonFormat.json.parseToJsonElement(call.receiveBoundedText().ifBlank { "{}" }).jsonObject
            val facts = body["facts"] as? JsonArray
            if (facts == null) {
                val result = know.put(JsonFormat.json.decodeFromJsonElement(FactDraft.serializer(), body))
                return@answering call.respond(HttpStatusCode.OK, JsonFormat.json.encodeToString(PutResult.serializer(), result))
            }
            val results = facts.map { know.put(JsonFormat.json.decodeFromJsonElement(FactDraft.serializer(), it)) }
            call.respond(HttpStatusCode.OK, JsonFormat.json.encodeToString(PutBatch.serializer(), PutBatch(results)))
        }
    }
}

// A bad request is the caller's to fix: the message says what; anything else is the daemon's usual 500.
private suspend fun ApplicationCall.answering(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: IllegalArgumentException) {
        fail(HttpStatusCode.BadRequest, e.message.orEmpty())
    } catch (e: SerializationException) {
        fail(HttpStatusCode.BadRequest, "bad request body: ${e.message.orEmpty().lineSequence().first()}")
    }
}

private suspend fun ApplicationCall.respond(status: HttpStatusCode, body: String) = respondText(body, ContentType.Application.Json, status)

private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) = respond(status, buildJsonObject { put("error", message) }.toString())
