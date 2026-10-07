package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.Trackers
import codeloupe.tracker.read.WriteReply
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `update`: sets fields and/or adds a comment on a tracker issue; answers with what changed, not the issue. */
class UpdateTool(private val trackers: Trackers) : Tool {
    override val name = "update"
    override val description = "Write to a tracker issue: set={Field: value} (State, Assignee, Priority, Type, summary, description or any custom field; " +
        "several values comma-separated; empty clears) and/or comment=<text>. Answers one short line: the fields that changed (old→new), " +
        "the comment id, the new state. The local mirror holds the new version at once."
    override val properties = Schema.properties(
        "id" to Schema.string("e.g. TER-5"),
        "set" to Schema.obj("Field name → new text, e.g. {\"State\": \"In Progress\"}"),
        "comment" to Schema.string("Text of a new comment (markdown)"),
    )
    override val required = listOf("id")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val id = args.string("id")?.trim().orEmpty()
        val (mirror, canonical) = trackers.mirror(id) ?: return "no tracker mirrors the project of '$id'; mirrored: ${trackers.projects().joinToString(", ")}"
        val set = fields(args.json["set"]) ?: return "set: an object of field name → text, e.g. {\"State\": \"Done\"}"
        val comment = args.string("comment")?.takeIf { it.isNotBlank() }
        if (set.isEmpty() && comment == null) return "nothing to write: pass set={Field: value} and/or comment=<text>"
        return WriteReply.render(mirror.write(canonical, set, comment), set.keys)
    }

    /** Field → text; a JSON null clears. Null when [set] is not an object of plain values. */
    private fun fields(set: JsonElement?): Map<String, String>? = when (set) {
        null, JsonNull -> emptyMap()
        is JsonObject -> set.entries.associate { (k, v) -> k.trim() to ((v as? JsonPrimitive ?: return null).takeIf { it !is JsonNull }?.content ?: "") }
            .takeIf { m -> m.keys.none { it.isEmpty() } }
        else -> null
    }
}
