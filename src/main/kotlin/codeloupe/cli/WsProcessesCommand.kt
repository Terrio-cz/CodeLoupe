package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.processes.ProcessRender
import codeloupe.processes.ProcessReport
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.jsonPrimitive

class WsProcessesCommand : CliktCommand(name = "processes") {
    private val workspace by option("--workspace", help = "Only this workspace (its directory name)")
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    override fun help(context: Context) =
        "The processes that work in a workspace directory (build daemons and workers, servers, shells) and the memory each workspace holds. Build tools of released workspaces are stopped by the reconciler."

    override fun run() {
        val (status, body) = DaemonClient(ConfigLoader.load()).send("GET", "/processes", timeout = null)
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (json) return echo(body.toString())
        echo(ProcessRender.text(JsonFormat.json.decodeFromJsonElement(ProcessReport.serializer(), body), workspace), trailingNewline = false)
    }
}
