package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import kotlinx.serialization.json.JsonPrimitive

class HierarchyCommand : ToolCommand("hierarchy") {
    private val name by argument(help = "Type, pkg.Type or Type.member")

    override fun help(context: Context) = "Supertypes and subtypes of a type, or overrides of a member."

    override fun arguments() = mapOf("name" to JsonPrimitive(name))
}
