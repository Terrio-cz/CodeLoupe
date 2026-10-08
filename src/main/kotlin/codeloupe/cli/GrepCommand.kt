package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class GrepCommand : ToolCommand("grep") {
    private val pattern by argument(help = "Text to find (a regex with --regex)")
    private val regex by option(help = "Treat the pattern as a regular expression").flag()
    private val ignoreCase by option("--ignore-case", "-i", help = "Case-insensitive match").flag()
    private val module by option(help = "Module path prefix, e.g. services/billing")
    private val test by option(help = "true = only test sources, false = exclude them").boolean()
    private val limit by option(help = "At most this many hit lines (default 40)").int()

    override fun help(context: Context) = "Text search with the enclosing declaration of every hit."

    override fun arguments() = mapOf(
        "pattern" to JsonPrimitive(pattern), "regex" to regex.takeIf { it }?.let(::JsonPrimitive),
        "ignoreCase" to ignoreCase.takeIf { it }?.let(::JsonPrimitive), "module" to module?.let(::JsonPrimitive),
        "test" to test?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive),
    )
}
