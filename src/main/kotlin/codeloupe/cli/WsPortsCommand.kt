package codeloupe.cli

import codeloupe.JsonFormat
import codeloupe.config.ConfigLoader
import codeloupe.ports.PortReport
import codeloupe.ports.PortState
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.jsonPrimitive

/** `codeloupe ws ports` lists the allocations with what holds each port; `allocate <name>` and `free [name]` act on the workspace of the directory. */
class WsPortsCommand : CliktCommand(name = "ports") {
    override val invokeWithoutSubcommand = true
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    init {
        subcommands(WsPortsAllocateCommand(), WsPortsFreeCommand())
    }

    override fun help(context: Context) =
        "Ports per workspace from workspaces.ports.range: who has which, what holds it now, and conflicts with the owning process or container."

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val (status, body) = DaemonClient(ConfigLoader.load()).send("GET", "/ports", timeout = null)
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        if (json) return echo(body.toString())
        val report = JsonFormat.json.decodeFromJsonElement(PortReport.serializer(), body)
        echo(report.range?.let { "range $it" } ?: "no range configured (workspaces.ports.range)")
        for (s in report.allocations) {
            val a = s.allocation
            echo("${a.port}  ${a.workspace} (${a.repo})  ${a.name}  ${s.state.name.lowercase().replace('_', '-')}" + (s.usedBy?.let { "  $it" } ?: "") + if (s.state == PortState.CONFLICT) "  <- conflict" else "")
        }
        for (f in report.foreign) echo("${f.port}  foreign  ${f.usedBy}")
        for (p in report.problems) echo("! $p")
    }
}
