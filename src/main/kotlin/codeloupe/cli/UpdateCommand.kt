package codeloupe.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class UpdateCommand : ToolCommand("update") {
    private val id by argument(help = "Issue id, e.g. TER-5")
    private val set by option("--set", help = "Field=value, e.g. State=\"In Progress\" (repeatable; empty value clears)").multiple()
    private val comment by option(help = "Add a comment with this text")
    private val check by option("--check", help = "Tick the one open '- [ ]' criterion of the description that contains this text")
    private val editCriterion by option("--edit-criterion", help = "Rewrite the one criterion that contains this text (needs --to)")
    private val to by option("--to", help = "The new text of the criterion named by --edit-criterion")

    override fun help(context: Context) = "Set fields, add a comment and/or tick or rewrite an acceptance criterion on a tracker issue; prints only what changed."

    override fun arguments(): Map<String, JsonElement?> {
        if (check != null && editCriterion != null) throw UsageError("--check and --edit-criterion are two writes: pass one")
        if (editCriterion != null && to == null) throw UsageError("--edit-criterion needs --to <new text>")
        if (to != null && editCriterion == null) throw UsageError("--to only goes with --edit-criterion")
        return mapOf(
            "id" to JsonPrimitive(id),
            "set" to set.takeIf { it.isNotEmpty() }?.let { pairs ->
                JsonObject(pairs.associate { p ->
                    if ('=' !in p) throw UsageError("--set takes Field=value, not '$p'")
                    p.substringBefore('=').trim() to JsonPrimitive(p.substringAfter('='))
                })
            },
            "comment" to comment?.let(::JsonPrimitive),
            "check" to check?.let(::JsonPrimitive),
            "edit_criterion" to editCriterion?.let(::JsonPrimitive),
            "to" to to?.let(::JsonPrimitive),
        )
    }
}
