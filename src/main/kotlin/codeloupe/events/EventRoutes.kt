package codeloupe.events

import codeloupe.JsonFormat
import codeloupe.daemon.receiveBoundedText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * `GET /events?since=&limit=`, the live stream `GET /events/stream` (server-sent events, resumes after `Last-Event-ID`),
 * and webhook subscriptions under `/webhooks`.
 */
fun Route.eventRoutes(bus: EventBus, webhooks: Webhooks, key: WebhookKey) {
    get("/events") {
        val since = call.parameters["since"]?.toLongOrNull()
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        val items = if (since == null) emptyList() else bus.since(since, limit)
        call.respondJson(
            buildJsonObject {
                put("lastSeq", bus.lastSeq())
                put("items", JsonFormat.json.encodeToJsonElement(ListSerializer(Event.serializer()), items))
            },
        )
    }
    get("/events/stream") {
        val since = call.request.headers["Last-Event-ID"]?.toLongOrNull() ?: call.parameters["since"]?.toLongOrNull() ?: bus.lastSeq()
        call.respondBytesWriter(ContentType.Text.EventStream) { stream(bus, since) }
    }
    get("/webhooks") {
        call.respondJson(buildJsonObject { put("items", JsonFormat.json.encodeToJsonElement(ListSerializer(Webhook.serializer()), webhooks.list())) })
    }
    post("/webhooks") {
        val body = runCatching { JsonFormat.json.parseToJsonElement(call.receiveBoundedText()).jsonObject }.getOrNull()
            ?: return@post call.respondJson(error("send {\"url\": …, \"events\": [\"job.finished\"]}"), HttpStatusCode.BadRequest)
        val url = (body["url"] as? JsonPrimitive)?.content.orEmpty()
        val events = (body["events"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        val webhook = try {
            webhooks.add(url, events)
        } catch (e: IllegalArgumentException) {
            return@post call.respondJson(error(e.message.orEmpty()), HttpStatusCode.BadRequest)
        }
        call.respondJson(
            buildJsonObject {
                put("webhook", JsonFormat.json.encodeToJsonElement(Webhook.serializer(), webhook))
                put("keyFile", key.file.toString())
            },
        )
    }
    delete("/webhooks/{id}") {
        val removed = webhooks.remove(call.parameters["id"].orEmpty())
        call.respondJson(buildJsonObject { put("removed", removed) }, if (removed) HttpStatusCode.OK else HttpStatusCode.NotFound)
    }
    get("/webhooks/deliveries") {
        val limit = (call.parameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
        call.respondJson(buildJsonObject { put("items", JsonFormat.json.encodeToJsonElement(ListSerializer(Delivery.serializer()), webhooks.deliveries(limit))) })
    }
}

/**
 * Stored events after [since], then live ones. The live subscription starts before the replay, so nothing falls
 * between them; whenever a live event skips a number (the shared buffer dropped some), the gap is read from the
 * store. A reader too slow for its own buffer is cut off and resumes with its `Last-Event-ID`.
 */
private suspend fun ByteWriteChannel.stream(bus: EventBus, since: Long) = coroutineScope {
    val buffered = Channel<Event>(STREAM_BUFFER)
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        bus.live.collect { if (buffered.trySend(it).isFailure) buffered.close() }
    }
    writeStringUtf8("retry: 3000\n: connected\n\n")
    var last = replay(bus, since)
    try {
        for (event in buffered) {
            if (event.seq > last + 1) last = replay(bus, last)
            if (event.seq <= last) continue
            write(event)
            flush()
            last = event.seq
        }
    } finally {
        collector.cancel()
    }
}

/** Writes the stored events after [after]; the last `seq` written. */
private suspend fun ByteWriteChannel.replay(bus: EventBus, after: Long): Long {
    var last = after
    while (true) {
        val page = bus.since(last, REPLAY_PAGE)
        page.forEach { write(it) }
        last = page.lastOrNull()?.seq ?: break
    }
    flush()
    return last
}

private suspend fun ByteWriteChannel.write(event: Event) {
    writeStringUtf8("id: ${event.seq}\nevent: ${event.type}\ndata: ${JsonFormat.json.encodeToString(Event.serializer(), event)}\n\n")
}

private fun error(message: String) = buildJsonObject { put("error", message) }

private suspend fun ApplicationCall.respondJson(body: JsonObject, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(body.toString(), ContentType.Application.Json, status)

private const val STREAM_BUFFER = 512
private const val REPLAY_PAGE = 500
