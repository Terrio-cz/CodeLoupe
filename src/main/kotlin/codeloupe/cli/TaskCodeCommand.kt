package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

class TaskCodeCommand : ToolCommand("task_code") {
    private val query by argument(help = "A task id (TER-5), a declaration (Type.member) or a file path")
    private val limit by option(help = "At most this many lines (default 60)").int()

    override fun help(context: Context) = "The code a task touched or will touch, or the tasks that touched a declaration or file."

    override fun arguments() = mapOf("query" to JsonPrimitive(query), "limit" to limit?.let(::JsonPrimitive))
}
