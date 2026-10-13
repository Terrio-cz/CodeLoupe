package codeloupe.brain

import codeloupe.JsonFormat
import codeloupe.daemon.receiveBoundedText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The brain index behind the daemon token like every route that is not a read-only code query. All of them name the workspace by its directory, which
 * must be a registered brain.
 * `POST /brain/rank`: [BrainRankRequest] -> [BrainRankResult]. `POST /brain/resolve`: [ResolveRequest] -> [ResolveResult].
 * `POST /brain/risk`: [RiskRequest] -> [RiskResult]. `POST /brain/hygiene` and `POST /brain/backfill`: `{"workspace": "..."}` -> [HygieneReport] / [BackfillResult].
 */
fun Route.brainRoutes(service: BrainService) {
    post("/brain/rank") { call.answering { respond(BrainRankResult.serializer(), service.rank(body(BrainRankRequest.serializer()))) } }
    post("/brain/risk") { call.answering { respond(RiskResult.serializer(), service.risk(body(RiskRequest.serializer()))) } }
    post("/brain/resolve") { call.answering { respond(ResolveResult.serializer(), service.resolve(body(ResolveRequest.serializer()))) } }
    post("/brain/hygiene") { call.answering { respond(HygieneReport.serializer(), service.hygiene(body(WorkspaceRequest.serializer()).workspace)) } }
    post("/brain/backfill") { call.answering { respond(BackfillResult.serializer(), service.backfill(body(WorkspaceRequest.serializer()).workspace)) } }
}

@kotlinx.serialization.Serializable
private data class WorkspaceRequest(val workspace: String)

private class Exchange(val call: ApplicationCall) {
    suspend fun <T> body(serializer: KSerializer<T>): T = JsonFormat.json.decodeFromString(serializer, call.receiveBoundedText().ifBlank { "{}" })

    suspend fun <T> respond(serializer: KSerializer<T>, value: T) = call.respondJson(HttpStatusCode.OK, JsonFormat.json.encodeToString(serializer, value))
}

// A bad request is the caller's to fix, an unknown workspace is not found, a brain that cannot be read is unavailable; anything else is the daemon's usual 500.
private suspend fun ApplicationCall.answering(block: suspend Exchange.() -> Unit) {
    try {
        Exchange(this).block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BrainException) {
        respondJson(if (e.message.orEmpty().startsWith("no registered workspace")) HttpStatusCode.NotFound else HttpStatusCode.ServiceUnavailable, buildJsonObject { put("error", e.message.orEmpty()) }.toString())
    } catch (e: SerializationException) {
        respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", "bad request body: ${e.message.orEmpty().lineSequence().first()}") }.toString())
    } catch (e: IllegalArgumentException) {
        respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("error", e.message.orEmpty()) }.toString())
    }
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) = respondText(body, ContentType.Application.Json, status)
