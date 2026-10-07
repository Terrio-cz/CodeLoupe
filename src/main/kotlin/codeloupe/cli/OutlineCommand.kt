package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.serialization.json.JsonPrimitive

class OutlineCommand : ToolCommand("outline") {
    private val target by argument(help = "File path (or unique suffix) or type name")

    override fun help(context: Context) = "Members of a file or a type with line ranges, no bodies."

    override fun arguments() = mapOf("target" to JsonPrimitive(target))
}
