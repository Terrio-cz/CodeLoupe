package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class TasksCommand : ToolCommand("tasks") {
    private val query by argument(help = "Filters and words, an issue id (graph) or epic ids (progress)").optional()
    private val mode by option(help = "list (default), graph, ready or progress").choice("list", "graph", "ready", "progress")
    private val depth by option(help = "Graph depth 1-3 (default 1)").int()
    private val limit by option(help = "At most this many lines (default 40)").int()

    override fun help(context: Context) = "Tasks from the local tracker mirror: list, graph, ready or epic progress."

    override fun arguments() = mapOf(
        "query" to query?.let(::JsonPrimitive), "mode" to mode?.let(::JsonPrimitive),
        "depth" to depth?.let(::JsonPrimitive), "limit" to limit?.let(::JsonPrimitive),
    )
}
