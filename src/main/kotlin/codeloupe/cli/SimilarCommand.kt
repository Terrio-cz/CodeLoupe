package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class SimilarCommand : ToolCommand("similar") {
    private val summary by argument(help = "Title of the issue you are about to create")
    private val description by option(help = "Its description or scope")
    private val project by option(help = "Only this project's mirror, e.g. TER")
    private val limit by option(help = "At most this many tasks (default 5)").int()

    override fun help(context: Context) = "Tasks of the local mirror that look like a draft issue; run it before creating one."

    override fun arguments() = mapOf(
        "summary" to JsonPrimitive(summary), "description" to description?.let(::JsonPrimitive),
        "project" to project?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive),
    )
}
