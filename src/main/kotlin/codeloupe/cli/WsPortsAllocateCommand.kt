package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WsPortsAllocateCommand : WsOwnedCommand("allocate") {
    private val portName by argument(name = "NAME", help = "What the port is for: app, postgres, …")

    override fun help(context: Context) = "The port of NAME in this workspace: the recorded one, or a free one from the range. Prints the port number."

    override fun run() {
        val owner = ownership()
        val (status, body) = DaemonClient(ConfigLoader.load()).send(
            "POST", "/ports/allocate",
            buildJsonObject {
                put("repo", owner.repo)
                put("workspace", owner.workspace)
                put("name", portName)
            },
            timeout = null,
        )
        if (status != 200) fail(body["error"]?.jsonPrimitive?.content ?: "HTTP $status")
        echo(body.getValue("port").jsonPrimitive.content)
    }
}
