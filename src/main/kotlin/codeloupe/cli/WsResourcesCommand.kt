package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.docker.OwnershipClass
import codeloupe.docker.ResourceRender
import codeloupe.docker.ResourceReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import kotlinx.serialization.json.jsonPrimitive

class WsResourcesCommand : CliktCommand(name = "resources") {
    private val owner by option("--class", help = "Only owned, adopted or unowned resources").enum<OwnershipClass> { it.name.lowercase() }
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    override fun help(context: Context) =
        "Every container, image, volume and network of the local Docker, by owner: CodeLoupe labels, an adoption rule of the config, or unowned (reported only, never touched)."

    override fun run() {
        val (status, body) = DaemonClient(ConfigLoader.load()).send("GET", "/resources", timeout = null)
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (json) return echo(body.toString())
        echo(ResourceRender.text(JsonFormat.json.decodeFromJsonElement(ResourceReport.serializer(), body), owner?.let { setOf(it) } ?: OwnershipClass.entries.toSet()), trailingNewline = false)
    }
}
