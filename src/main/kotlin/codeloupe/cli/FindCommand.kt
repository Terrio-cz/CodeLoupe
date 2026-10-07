package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.boolean
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class FindCommand : ToolCommand("find") {
    private val q by argument(help = "Name, Type.member, package.Type or glob with * ?")
    private val kind by option(help = "class, interface, object, enum, companion, annotation, fun, property, constructor, enum_entry, typealias")
    private val module by option(help = "Module path prefix, e.g. services/billing")
    private val test by option(help = "true = only test sources, false = exclude them").boolean()
    private val limit by option(help = "At most this many lines (default 30)").int()
    private val locals by option(help = "Include local declarations").flag()

    override fun help(context: Context) = "Find declarations by name, qualified name or glob."

    override fun arguments() = mapOf(
        "q" to JsonPrimitive(q), "kind" to kind?.let(::JsonPrimitive), "module" to module?.let(::JsonPrimitive),
        "test" to test?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive), "locals" to locals.takeIf { it }?.let(::JsonPrimitive),
    )
}
