package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.CriterionEdit
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.WriteReply
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `update`: sets fields, adds a comment and/or ticks or rewrites one acceptance criterion of a tracker issue; answers with
 * what changed, not the issue.
 */
class UpdateTool(private val trackers: Trackers) : Tool {
    override val name = "update"
    override val mutating = true
    override val description = "Write to a tracker issue: set={Field: value} (State, Assignee, Priority, Type, summary, description or any custom field; " +
        "several values comma-separated; empty clears), comment=<text>, check=<text of one open '- [ ]' criterion> to tick it, or " +
        "edit_criterion=<text of one criterion> with to=<new text>. A criterion text that matches none or several is refused, nothing is written. " +
        "Answers one short line: the fields that changed (old→new), the ticked criterion, the comment id, the new state. " +
        "The local mirror holds the new version at once."
    override val properties = Schema.properties(
        "id" to Schema.string("e.g. TER-5"),
        "set" to Schema.obj("Field name → new text, e.g. {\"State\": \"In Progress\"}"),
        "comment" to Schema.string("Text of a new comment (markdown)"),
        "check" to Schema.string("A part of the text of one open acceptance criterion; that line is ticked"),
        "edit_criterion" to Schema.string("A part of the text of one acceptance criterion (open or ticked) to rewrite; needs 'to'"),
        "to" to Schema.string("The new text of the criterion named by edit_criterion"),
    )
    override val required = listOf("id")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val id = args.string("id")?.trim().orEmpty()
        val (mirror, canonical) = trackers.mirror(id) ?: return "no tracker mirrors the project of '$id'; mirrored: ${trackers.projects().joinToString(", ")}"
        val set = fields(args.json["set"]) ?: return "set: an object of field name → text, e.g. {\"State\": \"Done\"}"
        val comment = args.string("comment")?.takeIf { it.isNotBlank() }
        val check = args.string("check")
        val rewrite = args.string("edit_criterion")
        val to = args.string("to")
        if (check != null && rewrite != null) return "check and edit_criterion are two writes: pass one"
        if (rewrite != null && to == null) return "edit_criterion needs to=<the new text of the criterion>"
        if (to != null && rewrite == null) return "to only goes with edit_criterion"
        if ((check != null || rewrite != null) && set.keys.any { it.equals("description", ignoreCase = true) }) return "a criterion edit rewrites the description: do not set it as well"
        if (set.isEmpty() && comment == null && check == null && rewrite == null) {
            return "nothing to write: pass set={Field: value}, comment=<text>, check=<criterion> and/or edit_criterion=<criterion> with to=<text>"
        }
        var note: String? = null
        val edit: ((String) -> String)? = when {
            check != null -> { description -> CriterionEdit.tick(description, check).also { note = "ticked: ${it.text}" }.description }
            rewrite != null -> { description -> CriterionEdit.rewrite(description, rewrite, to!!).also { note = "criterion now: ${it.text}" }.description }
            else -> null
        }
        val result = mirror.write(canonical, set, comment, edit)
        return WriteReply.render(result, set.keys, note)
    }

    /** Field → text; a JSON null clears. Null when [set] is not an object of plain values. */
    private fun fields(set: JsonElement?): Map<String, String>? = when (set) {
        null, JsonNull -> emptyMap()
        is JsonObject -> set.entries.associate { (k, v) -> k.trim() to ((v as? JsonPrimitive ?: return null).takeIf { it !is JsonNull }?.content ?: "") }
            .takeIf { m -> m.keys.none { it.isEmpty() } }
        else -> null
    }
}
