package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WebhookListCommand : CliktCommand(name = "list") {
    override fun help(context: Context) = "List webhook subscriptions."

    override fun run() {
        val (_, body) = DaemonClient(ConfigLoader.load()).send("GET", "/webhooks")
        val lines = body["items"]?.jsonArray.orEmpty().map { it.jsonObject }.map { w ->
            val events = w.getValue("events").jsonArray.map { it.jsonPrimitive.content }.ifEmpty { listOf("all events") }
            "${w.getValue("id").jsonPrimitive.content}  ${w.getValue("url").jsonPrimitive.content}  ${events.joinToString()}"
        }
        echo(lines.joinToString("\n").ifEmpty { "no webhooks" })
    }
}
