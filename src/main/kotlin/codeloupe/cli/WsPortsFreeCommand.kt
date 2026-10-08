package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class WsPortsFreeCommand : WsOwnedCommand("free") {
    private val portName by argument(name = "NAME", help = "The port to forget; without it all of the workspace's").optional()

    override fun help(context: Context) = "Forget a port (or all ports) of this workspace. Nothing is stopped; the port can be allocated to another workspace."

    override fun run() {
        val owner = ownership()
        val (status, body) = DaemonClient(ConfigLoader.load()).send(
            "POST", "/ports/free",
            buildJsonObject {
                put("repo", owner.repo)
                put("workspace", owner.workspace)
                portName?.let { put("name", it) }
            },
            timeout = null,
        )
        if (status != 200) fail(body["error"]?.jsonPrimitive?.content ?: "HTTP $status")
        echo("freed ${body.getValue("freed").jsonPrimitive.content}")
    }
}
