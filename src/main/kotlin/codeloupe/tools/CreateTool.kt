package codeloupe.tools

import codeloupe.repo.Registry
import codeloupe.tracker.Trackers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `create`: a new issue as a subtask of an epic, in the epic's project. */
class CreateTool(private val trackers: Trackers) : Tool {
    override val name = "create"
    override val mutating = true
    override val description = "Create a tracker issue under an epic, in the epic's project (check 'similar' first). summary and description are required; " +
        "type, priority and set={Field: value} are the tracker's own fields and values (some projects require more, e.g. a component), " +
        "assignee defaults to the user the token belongs to. Answers one line: the new id."
    override val properties = Schema.properties(
        "epic" to Schema.string("The epic the new issue becomes a subtask of, e.g. TER-162"),
        "summary" to Schema.string("Title"),
        "description" to Schema.string("Description (markdown)"),
        "type" to Schema.string("The tracker's own type, e.g. Feature, Bug"),
        "priority" to Schema.string("e.g. Normal, Major"),
        "assignee" to Schema.string("Login; default: the token's user"),
        "set" to Schema.obj("Further fields the project requires, as for update, e.g. {\"Component\": \"Public API\"}"),
    )
    override val required = listOf("epic", "summary")
    override val needsRoot = false

    override suspend fun answer(registry: Registry, root: String, args: ToolArgs): String {
        val epic = args.string("epic")?.trim().orEmpty()
        val (mirror, canonical) = trackers.mirror(epic) ?: return "no tracker mirrors the project of '$epic'; mirrored: ${trackers.projects().joinToString(", ")}"
        val summary = args.string("summary")?.trim().orEmpty().ifEmpty { return "pass the summary of the new issue" }
        val extra = (args.json["set"] as? JsonObject).orEmpty().entries.associate { (k, v) -> k.trim() to ((v as? JsonPrimitive)?.content.orEmpty()) }
        val fields = extra + listOf("type" to "Type", "priority" to "Priority", "assignee" to "Assignee")
            .mapNotNull { (key, name) -> args.string(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()
        val made = mirror.create(canonical, summary, args.string("description").orEmpty(), fields)
        return "${made.id} created under $canonical" + listOfNotNull(made.type, made.priority).joinToString("") { " · $it" }
    }
}
