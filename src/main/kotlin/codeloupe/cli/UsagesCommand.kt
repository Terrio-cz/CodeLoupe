package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class UsagesCommand : ToolCommand("usages") {
    private val name by argument(help = "Type.member, member(ParamType, …) or pkg.Type")
    private val all by option(help = "Also list references that resolve to other declarations").flag()
    private val limit by option(help = "At most this many hit lines (default 40)").int()

    override fun help(context: Context) = "References to a declaration, grouped, marked exact (=) or candidate (?)."

    override fun arguments() = mapOf(
        "name" to JsonPrimitive(name), "all" to all.takeIf { it }?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive),
    )
}
