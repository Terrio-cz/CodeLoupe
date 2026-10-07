package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonPrimitive

class SymbolCommand : ToolCommand("symbol") {
    private val name by argument(help = "Type.member, member(ParamType, …), pkg.Type or path/File.kt:line")
    private val full by option(help = "Whole body even for large types").flag()
    private val all by option(help = "Return every match instead of listing ambiguous ones").flag()

    override fun help(context: Context) = "Source of one declaration: KDoc, annotations and body."

    override fun arguments() = mapOf(
        "name" to JsonPrimitive(name), "full" to full.takeIf { it }?.let(::JsonPrimitive), "all" to all.takeIf { it }?.let(::JsonPrimitive),
    )
}
