package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class CallsCommand : ToolCommand("calls") {
    private val name by argument(help = "Type.member, member(ParamType, …) or pkg.Type")
    private val callees by option(help = "What it calls instead of who calls it").flag()
    private val depth by option(help = "Tree depth 1-3 (default 2)").int()
    private val limit by option(help = "At most this many nodes (default 40)").int()

    override fun help(context: Context) = "Call tree: callers (default) or callees of a declaration."

    override fun arguments() = mapOf(
        "name" to JsonPrimitive(name), "direction" to callees.takeIf { it }?.let { JsonPrimitive("callees") },
        "depth" to depth?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive),
    )
}
