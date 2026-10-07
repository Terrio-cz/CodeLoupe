package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument

class WebhookRemoveCommand : CliktCommand(name = "remove") {
    private val id by argument(help = "Webhook id")

    override fun help(context: Context) = "Remove a subscription; its pending deliveries stop."

    override fun run() {
        val (status, _) = DaemonClient(ConfigLoader.load()).send("DELETE", "/webhooks/$id")
        if (status != 200) {
            echo("no webhook $id", err = true)
            throw ProgramResult(1)
        }
        echo("removed $id")
    }
}
