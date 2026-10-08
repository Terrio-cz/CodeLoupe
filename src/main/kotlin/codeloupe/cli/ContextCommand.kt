package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class ContextCommand : ToolCommand("context") {
    private val name by argument(help = "Type.member, member(ParamType, …), pkg.Type")
    private val full by option(help = "Whole body even for large types").flag()
    private val limit by option(help = "At most this many callers and callees each (default 20)").int()

    override fun help(context: Context) = "A declaration's source, callers and callees in one answer."

    override fun arguments() = mapOf(
        "name" to JsonPrimitive(name), "full" to full.takeIf { it }?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive),
    )
}
