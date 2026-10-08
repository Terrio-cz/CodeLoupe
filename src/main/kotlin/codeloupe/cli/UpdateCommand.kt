package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class UpdateCommand : ToolCommand("update") {
    private val id by argument(help = "Issue id, e.g. TER-5")
    private val set by option("--set", help = "Field=value, e.g. State=\"In Progress\" (repeatable; empty value clears)").multiple()
    private val comment by option(help = "Add a comment with this text")

    override fun help(context: Context) = "Set fields and/or add a comment on a tracker issue; prints only what changed."

    override fun arguments() = mapOf(
        "id" to JsonPrimitive(id),
        "set" to set.takeIf { it.isNotEmpty() }?.let { pairs ->
            JsonObject(pairs.associate { p ->
                if ('=' !in p) throw UsageError("--set takes Field=value, not '$p'")
                p.substringBefore('=').trim() to JsonPrimitive(p.substringAfter('='))
            })
        },
        "comment" to comment?.let(::JsonPrimitive),
    )
}
