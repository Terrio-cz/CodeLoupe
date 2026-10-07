package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.events.Delivery
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.jsonArray

class WebhookDeliveriesCommand : CliktCommand(name = "deliveries") {
    private val limit by option(help = "How many of the latest (default 20)").int().default(20)

    override fun help(context: Context) = "The delivery log: newest first, with attempts and the last status or error."

    override fun run() {
        val (_, body) = DaemonClient(ConfigLoader.load()).send("GET", "/webhooks/deliveries?limit=$limit")
        val deliveries = body["items"]?.jsonArray.orEmpty().map { JsonFormat.json.decodeFromJsonElement(Delivery.serializer(), it) }
        echo(
            deliveries.joinToString("\n") { d ->
                "${d.id}  ${d.state}  ${d.attempts}×  ${d.lastStatus ?: d.lastError ?: "-"}  ${d.type} #${d.seq}  ${d.url}  ${d.updatedAt}"
            }.ifEmpty { "no deliveries" },
        )
    }
}
