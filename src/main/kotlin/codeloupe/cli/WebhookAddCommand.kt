package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WebhookAddCommand : CliktCommand(name = "add") {
    private val url by argument(help = "http://127.0.0.1:<port>/… (or an https origin listed in remoteWebhooks)")
    private val events by option("--event", help = "Event type or prefix (job.*); repeat; default all").multiple()

    override fun help(context: Context) = "Subscribe a URL to events."

    override fun run() {
        val body = buildJsonObject {
            put("url", url)
            put("events", JsonArray(events.map(::JsonPrimitive)))
        }
        val (status, answer) = DaemonClient(ConfigLoader.load()).send("POST", "/webhooks", body)
        if (status != 200) {
            echo(answer["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        val webhook = answer.getValue("webhook").jsonObject
        echo("${webhook.getValue("id").jsonPrimitive.content} -> $url (${events.ifEmpty { listOf("all events") }.joinToString()})")
        echo("signed with HMAC-SHA256 (header x-codeloupe-signature) using the key in ${answer.getValue("keyFile").jsonPrimitive.content}")
    }
}
