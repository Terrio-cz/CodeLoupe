package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonPrimitive

class HierarchyCommand : ToolCommand("hierarchy") {
    private val name by argument(help = "Type, pkg.Type or Type.member")
    private val supers by option(help = "Also the direct supertypes").flag()
    private val deep by option(help = "Supertypes and subtypes transitively").flag()

    override fun help(context: Context) = "Direct subtypes of a type (--supers, --deep for more), or overrides of a member."

    override fun arguments() = mapOf("name" to JsonPrimitive(name), "supers" to supers.takeIf { it }?.let(::JsonPrimitive), "deep" to deep.takeIf { it }?.let(::JsonPrimitive))
}
