package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.serialization.json.JsonPrimitive

/** `code_tasks <symbol|path>`: the `task_code` tool read from the code side; the MCP catalog keeps the one tool. */
class CodeTasksCommand : ToolCommand("task_code", "code_tasks") {
    private val query by argument(help = "A declaration (Type.member, pkg.Type, File.kt:line) or a file path")
    private val limit by option(help = "At most this many lines (default 60)").int()

    override fun help(context: Context) = "The tasks that touched a declaration or file, newest first, with landing commits."

    override fun arguments() = mapOf("query" to JsonPrimitive(query), "limit" to limit?.let(::JsonPrimitive))
}
