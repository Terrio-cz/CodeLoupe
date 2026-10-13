package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class CreateCommand : ToolCommand("create") {
    private val epic by option(help = "Epic the new issue becomes a subtask of, e.g. TER-162").required()
    private val summary by option(help = "Title of the new issue").required()
    private val type by option(help = "Issue type, e.g. Task")
    private val priority by option(help = "Priority, e.g. Normal")
    private val assignee by option(help = "Assignee login (default: the token's user)")
    private val set by option("--set", help = "Another field the project requires, Field=value (repeatable), e.g. Component=\"Public API\"").multiple()
    private val descriptionFile by option("--description-file", help = "Markdown file with the description")

    override fun help(context: Context) = "Create a tracker issue under an epic; prints its id."

    override fun arguments() = mapOf(
        "epic" to JsonPrimitive(epic), "summary" to JsonPrimitive(summary),
        "type" to type?.let(::JsonPrimitive), "priority" to priority?.let(::JsonPrimitive), "assignee" to assignee?.let(::JsonPrimitive),
        "description" to descriptionFile?.let { JsonPrimitive(read(it)) },
        "set" to set.takeIf { it.isNotEmpty() }?.let { pairs ->
            JsonObject(pairs.associate { p ->
                if ('=' !in p) throw UsageError("--set takes Field=value, not '$p'")
                p.substringBefore('=').trim() to JsonPrimitive(p.substringAfter('='))
            })
        },
    )

    private fun read(file: String): String {
        val path = Path.of(file)
        if (!Files.isRegularFile(path)) throw UsageError("--description-file: no such file: $file")
        return Files.readString(path)
    }
}
