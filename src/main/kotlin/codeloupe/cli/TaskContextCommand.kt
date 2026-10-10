package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

class TaskContextCommand : ToolCommand("task_context") {
    private val id by argument(help = "Task id, e.g. TER-5")
    private val section by option(help = "Only this section: issue, description, comments, linked, open-criteria, touch, declarations, callers, prior, norms").multiple()
    private val view by option(help = "full (default) or digest").choice("full", "digest")
    private val since by option(help = "'none' sends everything again even when you already have it")

    override fun help(context: Context) = "A planner's starting pack for a task in one call; a repeated call answers what changed."

    override fun arguments() = mapOf(
        "id" to JsonPrimitive(id), "view" to view?.let(::JsonPrimitive),
        "sections" to section.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)) }, "since" to since?.let(::JsonPrimitive),
    )
}
