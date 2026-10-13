package codeloupe.cli

import codeloupe.config.ConfigLoader
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.jsonPrimitive

/** `codeloupe job slots` lists the slots; `set <name> <capacity>` changes one at once and in `config.json`. */
class JobSlotsCommand : CliktCommand(name = "slots") {
    override val invokeWithoutSubcommand = true
    private val json by option("--json", help = "The daemon's JSON answer").flag()

    init {
        subcommands(JobSlotsSetCommand())
    }

    override fun help(context: Context) =
        "The slots: capacity in force and where it comes from, the jobs holding each and the queue with wait times."

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val (status, body) = DaemonClient(ConfigLoader.load()).send("GET", "/jobs/slots")
        if (status != 200) {
            echo(body["error"]?.jsonPrimitive?.content ?: "HTTP $status", err = true)
            throw ProgramResult(1)
        }
        echo(if (json) body.toString() else body.getValue("text").jsonPrimitive.content)
    }
}
