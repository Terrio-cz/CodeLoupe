package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.serialization.json.jsonPrimitive

class JobCancelCommand : CliktCommand(name = "cancel") {
    private val id by argument(help = "Job id")

    override fun help(context: Context) = "Cancel a queued job, or end a running one and everything it started."

    override fun run() {
        val (status, body) = DaemonClient(ConfigLoader.load()).send("POST", "/jobs/$id/cancel")
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        echo("cancelling $id")
    }
}
