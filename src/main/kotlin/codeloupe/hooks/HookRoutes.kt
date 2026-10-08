package codeloupe.hooks

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val MAX_HOOK_BODY = 1L * 1024 * 1024

/** `POST /hook`: the JSON of a Claude Code hook in, the JSON it should print out; 204 when there is nothing to say. */
fun Route.hookRoutes(hooks: Hooks) {
    post("/hook") {
        val bytes = call.receiveChannel().readRemaining(MAX_HOOK_BODY + 1).readByteArray()
        val body = if (bytes.size > MAX_HOOK_BODY) null else runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
        val reply: JsonObject? = body?.let { hooks.reply(it) }
        if (reply == null) call.respond(HttpStatusCode.NoContent) else call.respondText(reply.toString(), ContentType.Application.Json)
    }
}
