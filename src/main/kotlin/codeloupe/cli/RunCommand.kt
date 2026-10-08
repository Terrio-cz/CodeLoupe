package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class RunCommand : ToolCommand("run") {
    private val command by argument(help = "The program and its arguments, after --: codeloupe run -- git log -30").multiple(required = true)
    private val raw by option(help = "The whole output instead of a summary").flag()
    private val timeout by option(help = "Seconds to wait for it to end (default 120; a longer command is a job)").int()

    override fun help(context: Context) = "Run a short command and print a summary of its output, with a handle to the rest."

    override fun arguments() = mapOf(
        "command" to JsonArray(command.map(::JsonPrimitive)), "raw" to raw.takeIf { it }?.let { JsonPrimitive(true) }, "timeoutSec" to timeout?.let(::JsonPrimitive),
    )
}
