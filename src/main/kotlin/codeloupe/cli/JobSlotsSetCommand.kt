package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class JobSlotsSetCommand : CliktCommand(name = "set") {
    private val name by argument(help = "Slot name, e.g. gradle-test")
    private val capacity by argument(help = "How many jobs may hold it at once (1 or more)").int()

    override fun help(context: Context) =
        "Set a slot's capacity now and in config.json: a raise starts waiting jobs at once, a cut ends no running job."

    override fun run() {
        val (status, body) = DaemonClient(ConfigLoader.load()).send("POST", "/jobs/slots/$name", buildJsonObject { put("capacity", capacity) })
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        echo(body.getValue("text").jsonPrimitive.content)
    }
}
