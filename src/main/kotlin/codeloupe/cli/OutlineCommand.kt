package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class OutlineCommand : ToolCommand("outline") {
    private val target by argument(help = "File path (or unique suffix) or type name; omit for the repository map").optional()
    private val focus by option(help = "Map only: comma-separated files or symbols the map is centred on")
    private val budget by option(help = "Map only: size in tokens (default 1500)").int()
    private val test by option(help = "Map only: true = include test sources").boolean()

    override fun help(context: Context) = "Members of a file or a type with line ranges, no bodies; without a target a ranked map of the repository."

    override fun arguments() = mapOf(
        "target" to target?.let(::JsonPrimitive), "focus" to focus?.let(::JsonPrimitive), "budget" to budget?.let(::JsonPrimitive), "test" to test?.let(::JsonPrimitive),
    )
}
